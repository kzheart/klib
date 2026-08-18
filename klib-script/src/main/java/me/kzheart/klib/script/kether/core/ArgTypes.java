/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 内置 Kether 参数强制转换。 */
public final class ArgTypes {

    public static final ArgType<Integer> INT = new CoerceType<>(ArgTypes::integer, "integer");
    public static final ArgType<Long> LONG = new CoerceType<>(ArgTypes::longValue, "long");
    public static final ArgType<Double> DOUBLE = new CoerceType<>(ArgTypes::doubleValue, "double");
    public static final ArgType<Boolean> BOOLEAN = new CoerceType<>(ArgTypes::booleanValue, "boolean");
    public static final ArgType<Duration> DURATION = new DurationType();
    public static final ArgType<ParsedAction<?>> ACTION = QuestReader::nextAction;

    private ArgTypes() {
    }

    public static <T> ArgType<List<T>> listOf(ArgType<T> argType) {
        return new ListType<>(argType);
    }

    private static Optional<Integer> integer(Object value) {
        try {
            return Optional.of(Integer.valueOf(String.valueOf(value)));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Long> longValue(Object value) {
        try {
            return Optional.of(Long.valueOf(String.valueOf(value)));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Double> doubleValue(Object value) {
        try {
            return Optional.of(Double.valueOf(String.valueOf(value)));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Boolean> booleanValue(Object value) {
        String text = String.valueOf(value).toLowerCase(Locale.ENGLISH);
        if ("true".equals(text) || "yes".equals(text) || "1".equals(text)) {
            return Optional.of(Boolean.TRUE);
        }
        if ("false".equals(text) || "no".equals(text) || "0".equals(text)) {
            return Optional.of(Boolean.FALSE);
        }
        return Optional.empty();
    }
}
