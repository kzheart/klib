/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

import java.time.Duration;

/** Java 门面保留泛型桥接方法上的 Exceptions 属性。 */
public final class DurationType implements ArgType<Duration> {
    @Override public Duration read(QuestReader reader) throws LocalizedException {
        return ArgumentReaders.duration(reader);
    }
}
