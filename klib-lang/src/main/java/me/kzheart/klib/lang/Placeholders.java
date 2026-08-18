package me.kzheart.klib.lang;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 占位符映射的可变参数工厂：{@code Placeholders.of("name", value, ...)}。 */
public final class Placeholders {
    private Placeholders() {
    }

    public static Map<String, Object> of(Object... pairs) {
        Objects.requireNonNull(pairs, "pairs");
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "Placeholders.of requires an even number of arguments (key, value, ...)");
        }
        if (pairs.length == 0) {
            return Collections.emptyMap();
        }
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) {
            Object key = pairs[index];
            if (!(key instanceof String)) {
                throw new IllegalArgumentException(
                        "Placeholder key at position " + index + " must be a string");
            }
            values.put((String) key, pairs[index + 1]);
        }
        return Collections.unmodifiableMap(values);
    }
}
