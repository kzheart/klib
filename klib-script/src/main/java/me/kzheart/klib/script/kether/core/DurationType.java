/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

import java.time.Duration;
import java.util.Locale;

/** 读取 ISO-8601 时长和 Kether 的简写时长形式。 */
public final class DurationType implements ArgType<Duration> {

    @Override
    public Duration read(QuestReader reader) throws LocalizedException {
        String value = reader.nextToken().toUpperCase(Locale.ENGLISH);
        if (!value.contains("T")) {
            if (value.contains("D")) {
                if (value.contains("H") || value.contains("M") || value.contains("S")) {
                    value = value.replace("D", "DT");
                }
            } else if (value.startsWith("P")) {
                value = "PT" + value.substring(1);
            } else {
                value = "T" + value;
            }
        }
        if (!value.startsWith("P")) {
            value = "P" + value;
        }
        try {
            return Duration.parse(value);
        } catch (RuntimeException exception) {
            throw LoadError.NOT_DURATION.create(value);
        }
    }
}
