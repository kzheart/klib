package me.kzheart.klib.config;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.SequenceNode;

/** 将能感知路径的 YAML 节点映射为 Java 8 POJO。 */
public final class YamlConfigMapper {
    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(YamlConfigMapper.class.getName());
    private static final java.util.regex.Pattern DURATION_SEGMENT =
            java.util.regex.Pattern.compile("([0-9]+)(ms|[smhd])");
    /** 迁移系统写入的保留根键，不参与未知键判定。 */
    private static final String SCHEMA_VERSION_KEY = "_schema-version";

    private final Map<Class<?>, ConfigConverter<?>> converters =
            new LinkedHashMap<Class<?>, ConfigConverter<?>>();
    /** 已告警过的“来源 + 路径”，保证同一个键在重复 reload 中只提示一次。 */
    private final Set<String> warnedUnknownKeys =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public <T> YamlConfigMapper registerConverter(Class<T> type, ConfigConverter<T> converter) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(converter, "converter");
        synchronized (converters) {
            converters.put(type, converter);
        }
        return this;
    }

    public <T> T read(ConfigNode node, Class<T> type) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(type, "type");
        Object mapped = convert(node, type);
        return type.cast(mapped);
    }

    private Object convert(ConfigNode node, Type targetType) {
        if (targetType instanceof Class<?>) {
            return convertClass(node, (Class<?>) targetType);
        }
        if (targetType instanceof ParameterizedType) {
            return convertParameterized(node, (ParameterizedType) targetType);
        }
        if (targetType instanceof WildcardType) {
            Type[] upperBounds = ((WildcardType) targetType).getUpperBounds();
            return convert(node, upperBounds.length == 0 ? Object.class : upperBounds[0]);
        }
        if (targetType instanceof GenericArrayType) {
            throw node.mappingError("generic arrays are not supported");
        }
        throw node.mappingError("unsupported target type " + targetType);
    }

    private Object convertClass(ConfigNode node, Class<?> type) {
        ConfigConverter<?> converter = findConverter(type);
        if (converter != null) {
            try {
                return converter.convert(node);
            } catch (ConfigException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw node.mappingError("custom converter failed for " + type.getName(), failure);
            }
        }

        Object raw = node.raw();
        if (raw == null) {
            if (type.isPrimitive()) {
                throw node.mappingError("null cannot be assigned to primitive " + type.getName());
            }
            return null;
        }
        if (type == Object.class) {
            return raw;
        }
        if (type == String.class) {
            if (!(raw instanceof String)) {
                throw node.mappingError("expected a string, got " + kind(raw));
            }
            return raw;
        }
        if (type == Boolean.TYPE || type == Boolean.class) {
            if (!(raw instanceof Boolean)) {
                throw node.mappingError("expected a boolean, got " + kind(raw));
            }
            return raw;
        }
        if (type == Character.TYPE || type == Character.class) {
            if (!(raw instanceof String) || ((String) raw).length() != 1) {
                throw node.mappingError("expected a single character");
            }
            return Character.valueOf(((String) raw).charAt(0));
        }
        if (isNumeric(type)) {
            return convertNumber(node, raw, type);
        }
        if (type == Duration.class) {
            if (!(raw instanceof String)) {
                throw node.mappingError("expected a duration string, got " + kind(raw));
            }
            return parseDuration(node, (String) raw);
        }
        if (type.isEnum()) {
            return convertEnum(node, raw, type);
        }
        if (type.isArray()) {
            return convertArray(node, type.getComponentType());
        }
        if (Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) {
            throw node.mappingError("collection type " + type.getName() + " must declare generic arguments");
        }
        return convertPojo(node, type);
    }

    private Object convertParameterized(ConfigNode node, ParameterizedType target) {
        Type rawType = target.getRawType();
        if (!(rawType instanceof Class<?>)) {
            throw node.mappingError("unsupported parameterized type " + target);
        }
        Class<?> rawClass = (Class<?>) rawType;
        if (Collection.class.isAssignableFrom(rawClass)) {
            Type[] arguments = target.getActualTypeArguments();
            return convertCollection(node, rawClass, arguments[0]);
        }
        if (Map.class.isAssignableFrom(rawClass)) {
            Type[] arguments = target.getActualTypeArguments();
            if (arguments[0] != String.class) {
                throw node.mappingError("only Map<String, ?> is supported");
            }
            return convertMap(node, rawClass, arguments[1]);
        }
        return convertPojo(node, rawClass);
    }

    private Object convertPojo(ConfigNode node, Class<?> type) {
        if (!(node.yamlNode() instanceof MappingNode)) {
            throw node.mappingError("expected a mapping for " + type.getName());
        }
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
            throw node.mappingError("cannot instantiate " + type.getName());
        }

        final Object instance;
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            instance = constructor.newInstance();
        } catch (ReflectiveOperationException failure) {
            throw node.mappingError(type.getName() + " requires an accessible no-argument constructor", failure);
        }

        Set<String> declaredNames = new LinkedHashSet<String>();
        for (Field field : fields(type)) {
            // 保留 static/transient/合成字段的名字：它们虽然不参与映射，
            // 但同名 YAML 键属于“有意忽略”，不应被报成拼写错误。
            declaredNames.add(field.getName());
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()) {
                continue;
            }
            ConfigNode fieldNode = node.child(field.getName());
            if (!fieldNode.exists()) {
                continue;
            }
            if (Modifier.isFinal(modifiers)) {
                throw fieldNode.mappingError("cannot assign final field " + field.getName());
            }
            Object value = convert(fieldNode, field.getGenericType());
            try {
                field.setAccessible(true);
                field.set(instance, value);
            } catch (IllegalAccessException failure) {
                throw fieldNode.mappingError("cannot assign field " + field.getName(), failure);
            } catch (IllegalArgumentException failure) {
                throw fieldNode.mappingError("invalid value for field " + field.getName(), failure);
            }
        }
        warnUnknownKeys(node, type, declaredNames);
        return instance;
    }

    /**
     * 报告当前 POJO 层级里“文档中存在但没有对应字段”的键。
     *
     * <p>判定只发生在 POJO 层：{@code Map<String, ?>} 与集合元素分别由
     * {@link #convertMap} 和 {@link #convertCollection} 处理，键完全由用户定义，
     * 因此不会在那里产生误报；嵌套 POJO 会在各自的递归调用中单独检查。
     */
    private void warnUnknownKeys(ConfigNode node, Class<?> type, Set<String> declaredNames) {
        String basePath = node.path();
        for (NodeTuple tuple : ((MappingNode) node.yamlNode()).getValue()) {
            String key = YamlDocument.scalarKey(tuple.getKeyNode());
            if (declaredNames.contains(key)) {
                continue;
            }
            if (basePath.isEmpty() && SCHEMA_VERSION_KEY.equals(key)) {
                continue;
            }
            String keyPath = basePath.isEmpty() ? key : basePath + "." + key;
            if (!warnedUnknownKeys.add(node.sourceName() + ' ' + keyPath)) {
                continue;
            }
            LOGGER.warning(ConfigLocations.prefix(
                    node.sourceName(),
                    keyPath,
                    ConfigLocations.startMark(tuple.getKeyNode()))
                    + ": unknown configuration key is ignored; " + type.getName()
                    + " has no matching field");
        }
    }

    private Object convertCollection(ConfigNode node, Class<?> rawClass, Type elementType) {
        if (!(node.yamlNode() instanceof SequenceNode)) {
            throw node.mappingError("expected a sequence, got " + kind(node.raw()));
        }
        Collection<Object> result = newCollection(node, rawClass);
        List<Node> values = ((SequenceNode) node.yamlNode()).getValue();
        for (int index = 0; index < values.size(); index++) {
            result.add(convert(node.indexed(values.get(index), index), elementType));
        }
        return result;
    }

    private Object convertMap(ConfigNode node, Class<?> rawClass, Type valueType) {
        if (!(node.yamlNode() instanceof MappingNode)) {
            throw node.mappingError("expected a mapping, got " + kind(node.raw()));
        }
        Map<String, Object> result = newMap(node, rawClass);
        for (NodeTuple tuple : ((MappingNode) node.yamlNode()).getValue()) {
            String key = YamlDocument.scalarKey(tuple.getKeyNode());
            result.put(key, convert(node.child(key, tuple.getValueNode()), valueType));
        }
        return result;
    }

    private Object convertArray(ConfigNode node, Class<?> componentType) {
        if (!(node.yamlNode() instanceof SequenceNode)) {
            throw node.mappingError("expected a sequence, got " + kind(node.raw()));
        }
        List<Node> values = ((SequenceNode) node.yamlNode()).getValue();
        Object array = java.lang.reflect.Array.newInstance(componentType, values.size());
        for (int index = 0; index < values.size(); index++) {
            java.lang.reflect.Array.set(
                    array,
                    index,
                    convert(node.indexed(values.get(index), index), componentType));
        }
        return array;
    }

    private ConfigConverter<?> findConverter(Class<?> type) {
        synchronized (converters) {
            return converters.get(type);
        }
    }

    private static List<Field> fields(Class<?> type) {
        List<Class<?>> hierarchy = new ArrayList<Class<?>>();
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            hierarchy.add(0, current);
        }
        List<Field> fields = new ArrayList<Field>();
        for (Class<?> current : hierarchy) {
            for (Field field : current.getDeclaredFields()) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static Collection<Object> newCollection(ConfigNode node, Class<?> type) {
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
            if (Set.class.isAssignableFrom(type)) {
                return new LinkedHashSet<Object>();
            }
            return new ArrayList<Object>();
        }
        Object value = instantiateContainer(node, type);
        if (!(value instanceof Collection<?>)) {
            throw node.mappingError("not a collection: " + type.getName());
        }
        @SuppressWarnings("unchecked")
        Collection<Object> collection = (Collection<Object>) value;
        return collection;
    }

    private static Map<String, Object> newMap(ConfigNode node, Class<?> type) {
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
            return new LinkedHashMap<String, Object>();
        }
        Object value = instantiateContainer(node, type);
        if (!(value instanceof Map<?, ?>)) {
            throw node.mappingError("not a map: " + type.getName());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value;
        return map;
    }

    private static Object instantiateContainer(ConfigNode node, Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException failure) {
            throw node.mappingError("cannot instantiate " + type.getName(), failure);
        }
    }

    private static Object convertNumber(ConfigNode node, Object raw, Class<?> type) {
        if (!(raw instanceof Number)) {
            throw node.mappingError("expected a number, got " + kind(raw));
        }
        Number number = (Number) raw;
        if (type == Byte.TYPE || type == Byte.class) {
            long value = integral(node, number);
            if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
                throw node.mappingError("number is outside byte range");
            }
            return Byte.valueOf((byte) value);
        }
        if (type == Short.TYPE || type == Short.class) {
            long value = integral(node, number);
            if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
                throw node.mappingError("number is outside short range");
            }
            return Short.valueOf((short) value);
        }
        if (type == Integer.TYPE || type == Integer.class) {
            long value = integral(node, number);
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw node.mappingError("number is outside integer range");
            }
            return Integer.valueOf((int) value);
        }
        if (type == Long.TYPE || type == Long.class) {
            return Long.valueOf(integral(node, number));
        }
        if (type == Float.TYPE || type == Float.class) {
            return Float.valueOf(number.floatValue());
        }
        if (type == Double.TYPE || type == Double.class) {
            return Double.valueOf(number.doubleValue());
        }
        throw node.mappingError("unsupported numeric target " + type.getName());
    }

    private static long integral(ConfigNode node, Number number) {
        if (number instanceof Float || number instanceof Double) {
            double value = number.doubleValue();
            if (!Double.isFinite(value) || value != Math.rint(value)) {
                throw node.mappingError("expected an integer, got " + number);
            }
        }
        return number.longValue();
    }

    private static Object convertEnum(ConfigNode node, Object raw, Class<?> enumType) {
        if (!(raw instanceof String)) {
            throw node.mappingError("expected an enum name, got " + kind(raw));
        }
        for (Object constant : enumType.getEnumConstants()) {
            if (((Enum<?>) constant).name().equalsIgnoreCase((String) raw)) {
                return constant;
            }
        }
        throw node.mappingError("unknown " + enumType.getSimpleName() + " value '" + raw + "'");
    }

    private static Duration parseDuration(ConfigNode node, String value) {
        try {
            if (value.startsWith("P") || value.startsWith("p")) {
                return Duration.parse(value.toUpperCase(java.util.Locale.ROOT));
            }
            String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
            java.util.regex.Matcher matcher = DURATION_SEGMENT.matcher(normalized);
            Duration result = Duration.ZERO;
            int end = 0;
            while (matcher.find()) {
                if (matcher.start() != end) {
                    throw new IllegalArgumentException("invalid duration segment");
                }
                long amount = Long.parseLong(matcher.group(1));
                String unit = matcher.group(2);
                if ("ms".equals(unit)) {
                    result = result.plusMillis(amount);
                } else if ("s".equals(unit)) {
                    result = result.plusSeconds(amount);
                } else if ("m".equals(unit)) {
                    result = result.plusMinutes(amount);
                } else if ("h".equals(unit)) {
                    result = result.plusHours(amount);
                } else {
                    result = result.plusDays(amount);
                }
                end = matcher.end();
            }
            if (end == 0 || end != normalized.length()) {
                throw new IllegalArgumentException("missing or invalid duration unit");
            }
            return result;
        } catch (RuntimeException failure) {
            throw node.mappingError("invalid duration '" + value + "'", failure);
        }
    }

    private static boolean isNumeric(Class<?> type) {
        return type == Byte.TYPE || type == Byte.class
                || type == Short.TYPE || type == Short.class
                || type == Integer.TYPE || type == Integer.class
                || type == Long.TYPE || type == Long.class
                || type == Float.TYPE || type == Float.class
                || type == Double.TYPE || type == Double.class;
    }

    private static String kind(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
