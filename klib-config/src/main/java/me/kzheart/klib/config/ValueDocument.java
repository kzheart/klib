package me.kzheart.klib.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/** 已解析原值的文档；不把第三方格式经 YAML 序列化再解析。 */
public final class ValueDocument {
    private final String sourceName;
    private final Map<String, Object> values;
    private final BiFunction<ConfigNode, Map<String, Object>, String> sectionWriter;

    private ValueDocument(String sourceName, Map<String, ?> values,
            BiFunction<ConfigNode, Map<String, Object>, String> sectionWriter) {
        this.sourceName = Objects.requireNonNull(sourceName, "sourceName");
        this.sectionWriter = Objects.requireNonNull(sectionWriter, "sectionWriter");
        this.values = map(copy(Objects.requireNonNull(values, "values"),
                new IdentityHashMap<Object, Boolean>(), sourceName));
    }

    /** 拷贝 Map/List 容器成为自有可变树，标量保留其类型与原对象。 */
    public static ValueDocument of(String sourceName, Map<String, ?> values,
            BiFunction<String, Map<String, Object>, String> sectionWriter) {
        Objects.requireNonNull(sectionWriter, "sectionWriter");
        return new ValueDocument(sourceName, values, (node, snapshot) -> sectionWriter.apply(node.path(), snapshot));
    }
    /** writer 收到当前节点视图，可通过 sameNode 辨识节替换；快照仍不可变。 */
    public static ValueDocument ofWithNodeWriter(String sourceName, Map<String, ?> values,
            BiFunction<ConfigNode, Map<String, Object>, String> sectionWriter) {
        return new ValueDocument(sourceName, values, sectionWriter);
    }
    public String sourceName() { return sourceName; }
    public ConfigNode root() { return new ConfigNode(this, values, true, "", ""); }
    public ConfigNode node(String path) {
        Objects.requireNonNull(path, "path");
        return path.isEmpty() ? root() : root().node(path);
    }

    String sectionText(ConfigNode node, Object value) {
        return Objects.requireNonNull(sectionWriter.apply(node, map(snapshot(value))), "sectionWriter result");
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }

    private static Object copy(Object value, IdentityHashMap<Object, Boolean> active, String source) {
        if (!(value instanceof Map<?, ?>) && !(value instanceof List<?>)) return value;
        if (active.put(value, Boolean.TRUE) != null) throw new ConfigException(source + ":<root>: recursive value containers are not supported");
        try {
            if (value instanceof Map<?, ?>) {
                Map<String, Object> result = new LinkedHashMap<String, Object>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    if (!(entry.getKey() instanceof String)) throw new ConfigException(source + ":<root>: value mapping keys must be strings");
                    result.put((String) entry.getKey(), copy(entry.getValue(), active, source));
                }
                return result;
            }
            List<Object> result = new ArrayList<Object>();
            for (Object element : (List<?>) value) result.add(copy(element, active, source));
            return result;
        } finally { active.remove(value); }
    }
    static Object snapshot(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> entry : map(value).entrySet()) result.put(entry.getKey(), snapshot(entry.getValue()));
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?>) {
            List<Object> result = new ArrayList<Object>();
            for (Object element : (List<?>) value) result.add(snapshot(element));
            return Collections.unmodifiableList(result);
        }
        return value;
    }
}
