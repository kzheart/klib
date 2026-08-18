package me.kzheart.klib.command;

import java.util.Collections;
import java.util.Map;
import me.kzheart.klib.lang.Placeholders;

final class MessagePlaceholders {
    private MessagePlaceholders() {
    }

    static Map<String, Object> none() {
        return Collections.emptyMap();
    }

    static Map<String, Object> of(Object... pairs) {
        return Placeholders.of(pairs);
    }
}
