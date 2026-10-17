/*
 * TabooLib 应用式 Kether 解析器的独立实现。
 * 原解析器使用 DataFixerUpper 高阶类型；本移植版本保留相同的 parse/map/fold 行为，
 * 且不会泄漏该依赖。
 * Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6
 */
package me.kzheart.klib.script.kether.core;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

/** 保留 Java 泛型 SAM 平台空性契约；组合行为由 Kotlin ParserCombinators 统一实现。 */
public final class Parser<T> {

    /** 延迟到动作收到执行帧后再进行的值计算。 */
    @FunctionalInterface
    public interface Action<T> {
        CompletableFuture<T> run(QuestContext.Frame frame);

        static <T> Action<T> point(T value) {
            return frame -> CompletableFuture.completedFuture(value);
        }
    }

    @FunctionalInterface public interface Function3<A, B, C, R> { R apply(A a, B b, C c); }
    @FunctionalInterface public interface Function4<A, B, C, D, R> { R apply(A a, B b, C c, D d); }
    @FunctionalInterface public interface Function5<A, B, C, D, E, R> { R apply(A a, B b, C c, D d, E e); }

    private final Function<QuestReader, Action<T>> reader;

    private Parser(Function<QuestReader, Action<T>> reader) {
        this.reader = reader;
    }

    public Action<T> read(QuestReader questReader) {
        return reader.apply(questReader);
    }

    public Parser<T> orElse(Parser<T> other) { return ParserCombinators.orElse(this, other); }
    public Parser<Optional<T>> optional() { return ParserCombinators.optional(this); }
    public Parser<List<T>> listOf() { return ParserCombinators.listOf(this); }
    public <R> Parser<R> map(Function<? super T, ? extends R> function) {
        return ParserCombinators.map(this, function);
    }
    public <B, R> Parser<R> fold(Parser<B> second, BiFunction<T, B, R> function) {
        return ParserCombinators.fold(this, second, function);
    }
    public <B, C, R> Parser<R> fold(Parser<B> second, Parser<C> third, Function3<T, B, C, R> function) {
        return ParserCombinators.fold(this, second, third, function);
    }
    public <B, C, D, R> Parser<R> fold(
            Parser<B> second, Parser<C> third, Parser<D> fourth, Function4<T, B, C, D, R> function) {
        return ParserCombinators.fold(this, second, third, fourth, function);
    }
    public <B, C, D, E, R> Parser<R> fold(
            Parser<B> second, Parser<C> third, Parser<D> fourth, Parser<E> fifth, Function5<T, B, C, D, E, R> function) {
        return ParserCombinators.fold(this, second, third, fourth, fifth, function);
    }

    public static <T> Parser<T> point(T value) {
        return new Parser<>(questReader -> Action.point(value));
    }

    public static <T> Parser<T> of(Function<QuestReader, T> parser) {
        return new Parser<>(questReader -> Action.point(parser.apply(questReader)));
    }

    public static <T> Parser<T> frame(Function<QuestReader, Action<T>> parser) {
        return new Parser<>(parser);
    }

    public static <A> QuestActionParser create(Function<Instance, Parser<Action<A>>> builder) {
        return build(builder.apply(Instance.INSTANCE));
    }

    public static <A> QuestActionParser build(Parser<Action<A>> parser) {
        return ParserCombinators.build(parser);
    }

    /** 作为 Parser.create 所用入口而保留的小型门面。 */
    public enum Instance {
        INSTANCE;
        public <T> Parser<T> point(T value) { return Parser.point(value); }
        public <T> Parser<T> of(Function<QuestReader, T> parser) { return Parser.of(parser); }
        public <T> Parser<T> frame(Function<QuestReader, Action<T>> parser) { return Parser.frame(parser); }
    }

}
