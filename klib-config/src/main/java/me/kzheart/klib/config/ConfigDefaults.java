package me.kzheart.klib.config;

import java.lang.reflect.*;
import java.util.*;
import me.kzheart.klib.config.annotation.*;
import org.yaml.snakeyaml.Yaml;

/** Explicit default YAML generation, including @Comment; never overwrites a live file. */
public final class ConfigDefaults {
    private ConfigDefaults() { }
    public static String yaml(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            String text = object(constructor.newInstance(), 0, Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>()));
            new YamlConfigMapper().read(YamlDocument.parse(type.getName(), text).root(), type);
            return text;
        } catch (ReflectiveOperationException failure) {
            throw new ConfigException("Cannot create defaults for " + type.getName(), failure);
        }
    }
    private static String object(Object object, int indent, Set<Object> visiting) throws IllegalAccessException {
        if (!visiting.add(object)) throw new ConfigException("Cyclic config defaults");
        StringBuilder out = new StringBuilder();
        Set<String> names = new HashSet<String>();
        for (Class<?> type = object.getClass(); type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers()) || field.isSynthetic()) continue;
                field.setAccessible(true);
                Key key = field.getAnnotation(Key.class);
                String name = key == null ? field.getName() : key.value();
                if (name.trim().isEmpty() || !names.add(name)) throw new ConfigException("Duplicate or blank config key " + name);
                Comment comment = field.getAnnotation(Comment.class);
                if (comment != null) for (String line : comment.value()) {
                    for (String part : line.split("\\R", -1)) out.append(spaces(indent)).append("# ").append(part).append('\n');
                }
                Object value = field.get(object);
                out.append(spaces(indent)).append(quoted(name)).append(':');
                if (value != null && pojo(value)) {
                    String nested = object(value, indent + 2, visiting);
                    if (nested.isEmpty()) out.append(" {}\n"); else out.append('\n').append(nested);
                } else {
                    out.append(' ').append(flow(normalize(value))).append('\n');
                }
            }
        }
        visiting.remove(object);
        return out.toString();
    }
    private static boolean pojo(Object value) {
        return !(value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Character || value instanceof Enum<?> || value instanceof java.time.Duration
                || value instanceof Map<?, ?> || value instanceof Iterable<?> || value.getClass().isArray());
    }
    private static Object normalize(Object value) {
        if (value instanceof Enum<?> || value instanceof Character || value instanceof java.time.Duration) return value.toString();
        if (value instanceof Map<?, ?>) {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) throw new ConfigException("Config map keys must be String");
                map.put((String) entry.getKey(), normalize(entry.getValue()));
            }
            return map;
        }
        if (value instanceof Iterable<?> || value != null && value.getClass().isArray()) {
            List<Object> list = new ArrayList<Object>();
            if (value instanceof Iterable<?>) for (Object element : (Iterable<?>) value) list.add(normalize(element));
            else for (int i = 0; i < Array.getLength(value); i++) list.add(normalize(Array.get(value, i)));
            return list;
        }
        if (value != null && pojo(value)) throw new ConfigException("Nested POJO collection defaults require a YAML resource");
        return value;
    }
    private static String flow(Object value) {
        org.yaml.snakeyaml.DumperOptions options = new org.yaml.snakeyaml.DumperOptions();
        options.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.FLOW);
        options.setDefaultScalarStyle(org.yaml.snakeyaml.DumperOptions.ScalarStyle.DOUBLE_QUOTED);
        options.setWidth(Integer.MAX_VALUE);
        options.setSplitLines(false);
        return new Yaml(options).dump(value).trim();
    }
    private static String quoted(String value) { return "'" + value.replace("'", "''") + "'"; }
    private static String spaces(int count) { char[] chars = new char[count]; Arrays.fill(chars, ' '); return new String(chars); }
}
