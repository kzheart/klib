package me.kzheart.klib.remote;

import java.util.Map;

/**
 * `/ingest/v1/batches` 中的一条不可变事件。
 * Java 边界保留非 final 静态方法及同包子类的静态隐藏契约。
 */
public class RemoteEvent {
    private final Map<String, Object> values;

    RemoteEvent(Map<String, ?> values) {
        this.values = immutableCopy(values);
    }

    /** 创建事件并覆盖调用方提供的保留字段。 */
    public static RemoteEvent of(String type, Map<String, ?> fields) {
        return RemoteEventFactory.create(type, fields);
    }

    /** 返回包括嵌套 JSON 值在内的不可变事件快照。 */
    public Map<String, Object> toMap() {
        return values;
    }

    static Map<String, Object> immutableCopy(Map<String, ?> source) {
        return RemoteEventFactory.snapshot(source);
    }
}
