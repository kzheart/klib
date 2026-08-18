package me.kzheart.klib.remote;

import java.util.Map;

/** 一次异常现场事件；相同 fingerprint 的 Incident 由服务端聚合为 Issue。 */
public final class Incident extends RemoteEvent {
    Incident(Map<String, ?> values) {
        super(values);
    }
}
