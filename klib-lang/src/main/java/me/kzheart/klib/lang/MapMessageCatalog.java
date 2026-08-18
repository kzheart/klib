package me.kzheart.klib.lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 支持字符串、字符串列表和预构建富文本的不可变消息目录。 */
public final class MapMessageCatalog implements MessageCatalog {
    private final Map<String, Object> entries;

    public MapMessageCatalog(Map<String, ?> entries) {
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, ?> entry : entries.entrySet()) {
            copy.put(entry.getKey(), normalize(entry.getKey(), entry.getValue()));
        }
        this.entries = Collections.unmodifiableMap(copy);
    }

    @Override
    public Optional<String> find(String key) {
        Object value = entries.get(key);
        return value instanceof String ? Optional.of((String) value) : Optional.<String>empty();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<List<String>> findLines(String key) {
        Object value = entries.get(key);
        return value instanceof List<?> ? Optional.of((List<String>) value) : Optional.<List<String>>empty();
    }

    @Override
    public Optional<RichText> findRich(String key) {
        Object value = entries.get(key);
        return value instanceof RichText ? Optional.of((RichText) value) : Optional.<RichText>empty();
    }

    @Override
    public Optional<Object> findAny(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    private static Object normalize(String key, Object value) {
        if (value instanceof String || value instanceof RichText) {
            return value;
        }
        if (value instanceof List<?>) {
            List<String> lines = new ArrayList<String>();
            for (Object line : (List<?>) value) {
                if (!(line instanceof String)) {
                    throw new IllegalArgumentException("Catalog list must contain strings: " + key);
                }
                lines.add((String) line);
            }
            return Collections.unmodifiableList(lines);
        }
        throw new IllegalArgumentException("Unsupported catalog value for " + key);
    }
}
