/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

import java.time.Duration;
import java.util.List;

/** 静态类型键保持 Java 平台泛型空性；词元转换由 Kotlin ArgumentReaders 完成。 */
public final class ArgTypes {
    public static final ArgType<Integer> INT = new CoerceType<>(ArgumentReaders::integer, "integer");
    public static final ArgType<Long> LONG = new CoerceType<>(ArgumentReaders::longValue, "long");
    public static final ArgType<Double> DOUBLE = new CoerceType<>(ArgumentReaders::doubleValue, "double");
    public static final ArgType<Boolean> BOOLEAN = new CoerceType<>(ArgumentReaders::booleanValue, "boolean");
    public static final ArgType<Duration> DURATION = new DurationType();
    public static final ArgType<ParsedAction<?>> ACTION = QuestReader::nextAction;
    private ArgTypes() { }
    public static <T> ArgType<List<T>> listOf(ArgType<T> argType) { return new ListType<>(argType); }
}
