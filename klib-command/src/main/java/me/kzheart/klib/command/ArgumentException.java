package me.kzheart.klib.command;

import java.util.Collections;
import java.util.Map;

@SuppressWarnings("serial")
final class ArgumentException extends Exception {
    private static final long serialVersionUID = 1L;

    private final String key;
    private final Map<String, Object> placeholders;

    ArgumentException(String key, Object... placeholderPairs) {
        super(key);
        this.key = key;
        this.placeholders = Collections.unmodifiableMap(
                MessagePlaceholders.of(placeholderPairs));
    }

    String key() {
        return key;
    }

    Map<String, Object> placeholders() {
        return placeholders;
    }
}
