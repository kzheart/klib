package me.kzheart.klib.remote;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Incident 前发生的有界事实记录。 */
public final class Breadcrumb {
    private final Map<String, Object> values;

    public Breadcrumb(String category, String message, Map<String, ?> context) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("occurred_at", Instant.now().toString());
        result.put("category", Texts.requireText(category, "category"));
        result.put("message", Texts.requireText(message, "message"));
        result.put("context", RemoteEvent.immutableCopy(context));
        values = java.util.Collections.unmodifiableMap(result);
    }

    public Map<String, Object> toMap() { return values; }
}
