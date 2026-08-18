package me.kzheart.klib.remote;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** `/ingest/v1/batches` 中的一条不可变事件。 */
public class RemoteEvent {
    private final Map<String, Object> values;

    RemoteEvent(Map<String, ?> values) {
        this.values = immutableCopy(values);
    }

    /** 创建事件并覆盖调用方提供的保留字段，生成稳定的 {@code event_id} 与时间。 */
    public static RemoteEvent of(String type, Map<String, ?> fields) {
        Objects.requireNonNull(fields, "fields");
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.putAll(immutableCopy(fields));
        values.put("event_id", EventIds.next());
        values.put("type", Texts.requireText(type, "type"));
        values.put("occurred_at", Instant.now().toString());
        return new RemoteEvent(values);
    }

    /** 返回包括嵌套 JSON 值在内的不可变事件快照。 */
    public Map<String, Object> toMap() {
        return values;
    }

    static Map<String, Object> immutableCopy(Map<String, ?> source) {
        return JsonValueSnapshot.copy(source);
    }
}
