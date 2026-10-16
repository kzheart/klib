package me.kzheart.klib.config;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import me.kzheart.klib.config.annotation.Comment;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** Explicit default YAML generation, including @Comment; never overwrites a live file. */
public final class ConfigDefaults {
    private ConfigDefaults() { }

    public static String yaml(Class<?> type) {
        Objects.requireNonNull(type, "type");
        try {
            Object defaults = ConfigTypePlan.of(type).defaults();
            Set<Object> visiting = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            String text = object(defaults, 0, visiting);
            if (text.isEmpty()) text = "{}\n";
            new YamlConfigMapper().read(YamlDocument.parse(type.getName(), text).root(), type);
            return text;
        } catch (IllegalAccessException failure) {
            throw new ConfigException("Cannot create defaults for " + type.getName(), failure);
        }
    }

    private static String object(Object object, int indent, Set<Object> visiting) throws IllegalAccessException {
        if (!visiting.add(object)) throw new ConfigException("Cyclic config defaults");
        try {
            StringBuilder out = new StringBuilder();
            for (ConfigTypePlan.FieldPlan declaration : ConfigTypePlan.of(object.getClass()).fields) {
                if (declaration.ignored) continue;
                Field field = declaration.field;
                field.setAccessible(true);
                Comment comment = declaration.comment;
                if (comment != null) for (String line : comment.value()) {
                    for (String part : line.split("\\R", -1)) {
                        out.append(spaces(indent)).append("# ").append(part).append('\n');
                    }
                }
                Object value = field.get(object);
                out.append(spaces(indent)).append(quoted(declaration.name)).append(':');
                if (value != null && pojo(value)) {
                    String nested = object(value, indent + 2, visiting);
                    if (nested.isEmpty()) out.append(" {}\n");
                    else out.append('\n').append(nested);
                } else {
                    out.append(' ').append(flow(normalize(value, visiting))).append('\n');
                }
            }
            return out.toString();
        } finally {
            visiting.remove(object);
        }
    }

    private static boolean pojo(Object value) {
        return !(value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Character || value instanceof Enum<?> || value instanceof Duration
                || value instanceof Map<?, ?> || value instanceof Iterable<?> || value.getClass().isArray());
    }

    private static Object normalize(Object value, Set<Object> visiting) throws IllegalAccessException {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Enum<?> || value instanceof Character || value instanceof Duration) return value.toString();
        if (!visiting.add(value)) throw new ConfigException("Cyclic config defaults");
        try {
            if (value instanceof Map<?, ?>) {
                Map<String, Object> map = new LinkedHashMap<String, Object>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    if (!(entry.getKey() instanceof String)) throw new ConfigException("Config map keys must be String");
                    map.put((String) entry.getKey(), normalize(entry.getValue(), visiting));
                }
                return map;
            }
            if (value instanceof Iterable<?> || value.getClass().isArray()) {
                List<Object> list = new ArrayList<Object>();
                if (value instanceof Iterable<?>) {
                    for (Object element : (Iterable<?>) value) list.add(normalize(element, visiting));
                } else {
                    for (int i = 0; i < Array.getLength(value); i++) list.add(normalize(Array.get(value, i), visiting));
                }
                return list;
            }
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            for (ConfigTypePlan.FieldPlan declaration : ConfigTypePlan.of(value.getClass()).fields) {
                if (declaration.ignored) continue;
                declaration.field.setAccessible(true);
                map.put(declaration.name, normalize(declaration.field.get(value), visiting));
            }
            return map;
        } finally {
            visiting.remove(value);
        }
    }

    private static String flow(Object value) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.FLOW);
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.DOUBLE_QUOTED);
        options.setWidth(Integer.MAX_VALUE);
        options.setSplitLines(false);
        return new Yaml(options).dump(value).trim();
    }

    private static String quoted(String value) { return "'" + value.replace("'", "''") + "'"; }
    private static String spaces(int count) { char[] chars = new char[count]; Arrays.fill(chars, ' '); return new String(chars); }
}
