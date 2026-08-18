package me.kzheart.klib.remote;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 对调用方 JSON 值建立有界、不可变且全有或全无的快照。 */
final class JsonValueSnapshot {
    private static final int MAX_DEPTH = 32;
    private static final int MAX_NODES = 4096;
    private static final int MAX_CONTAINER_ENTRIES = 256;

    private final IdentityHashMap<Object, Boolean> visiting =
            new IdentityHashMap<Object, Boolean>();
    private int nodes;

    static Map<String, Object> copy(Map<String, ?> source) {
        if (source == null) throw new NullPointerException("source");
        return new JsonValueSnapshot().map(source, 0);
    }

    private Object value(Object value, int depth) {
        if (value instanceof Map<?, ?>) return map((Map<?, ?>) value, depth);
        if (value instanceof List<?>) return list((List<?>) value, depth);
        if (value != null && value.getClass().isArray()) return array(value, depth);
        claimNode(depth);
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Number) return number((Number) value);
        throw new IllegalArgumentException("unsupported JSON snapshot value");
    }

    private Map<String, Object> map(Map<?, ?> source, int depth) {
        enter(source, depth);
        try {
            Map<String, Object> copy = new LinkedHashMap<String, Object>();
            int entries = 0;
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (++entries > MAX_CONTAINER_ENTRIES) reject("container entries");
                if (!(entry.getKey() instanceof String)) {
                    throw new IllegalArgumentException("JSON snapshot keys must be strings");
                }
                String key = Texts.requireText((String) entry.getKey(), "field name");
                copy.put(key, value(entry.getValue(), depth + 1));
            }
            return Collections.unmodifiableMap(copy);
        } finally {
            visiting.remove(source);
        }
    }

    private List<Object> list(List<?> source, int depth) {
        enter(source, depth);
        try {
            if (source.size() > MAX_CONTAINER_ENTRIES) reject("container entries");
            List<Object> copy = new ArrayList<Object>(source.size());
            for (int index = 0; index < source.size(); index++) {
                copy.add(value(source.get(index), depth + 1));
            }
            return Collections.unmodifiableList(copy);
        } finally {
            visiting.remove(source);
        }
    }

    private List<Object> array(Object source, int depth) {
        enter(source, depth);
        try {
            int length = Array.getLength(source);
            if (length > MAX_CONTAINER_ENTRIES) reject("container entries");
            List<Object> copy = new ArrayList<Object>(length);
            for (int index = 0; index < length; index++) {
                copy.add(value(Array.get(source, index), depth + 1));
            }
            return Collections.unmodifiableList(copy);
        } finally {
            visiting.remove(source);
        }
    }

    private static Number number(Number value) {
        if (value instanceof Double || value instanceof Float) {
            double number = value.doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) {
                throw new IllegalArgumentException("JSON snapshot number must be finite");
            }
            return value;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger
                || value instanceof BigDecimal) {
            return value;
        }
        throw new IllegalArgumentException("unsupported JSON snapshot number");
    }

    private void enter(Object value, int depth) {
        claimNode(depth);
        if (visiting.put(value, Boolean.TRUE) != null) reject("cycle");
    }

    private void claimNode(int depth) {
        if (depth > MAX_DEPTH) reject("depth");
        if (++nodes > MAX_NODES) reject("nodes");
    }

    private static void reject(String limit) {
        throw new IllegalArgumentException("JSON snapshot exceeds " + limit + " limit");
    }
}
