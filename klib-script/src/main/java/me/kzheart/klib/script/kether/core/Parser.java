/*
 * TabooLib 应用式 Kether 解析器的无依赖 Java 改编版。
 * 原解析器使用 DataFixerUpper 高阶类型；本移植版本保留相同的 parse/map/fold 行为，
 * 且不会泄漏该依赖。
 * Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6
 */
package me.kzheart.klib.script.kether.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

/** 用于组合有类型 Kether 动作参数的应用式解析器。 */
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

    public Parser<T> orElse(Parser<T> other) {
        return new Parser<>(questReader -> {
            // 保留本地快照，而不使用读取器唯一的共享标记槽，
            // 使嵌套 orElse 组合器不会覆盖彼此的回溯点。
            int index = questReader.getIndex();
            try {
                return reader.apply(questReader);
            } catch (RuntimeException exception) {
                questReader.setIndex(index);
                return other.reader.apply(questReader);
            }
        });
    }

    public Parser<Optional<T>> optional() {
        return map(Optional::ofNullable).orElse(point(Optional.empty()));
    }

    public Parser<List<T>> listOf() {
        return frame(questReader -> {
            questReader.expect("[");
            List<Action<T>> actions = new ArrayList<>();
            while (questReader.hasNext() && questReader.peek() != ']') {
                actions.add(reader.apply(questReader));
            }
            questReader.expect("]");
            return frame -> {
                List<CompletableFuture<T>> futures = new ArrayList<>(actions.size());
                for (Action<T> action : actions) futures.add(action.run(frame));
                CompletableFuture<?>[] array = futures.toArray(new CompletableFuture<?>[0]);
                return CompletableFuture.allOf(array).thenApply(ignored -> {
                    List<T> values = new ArrayList<>(futures.size());
                    for (CompletableFuture<T> future : futures) values.add(future.join());
                    return Collections.unmodifiableList(values);
                });
            };
        });
    }

    public <R> Parser<R> map(Function<? super T, ? extends R> function) {
        return new Parser<>(questReader -> {
            Action<T> action = reader.apply(questReader);
            return frame -> action.run(frame).thenApply(function);
        });
    }

    public <B, R> Parser<R> fold(Parser<B> second, BiFunction<T, B, R> function) {
        return new Parser<>(questReader -> {
            Action<T> firstAction = reader.apply(questReader);
            Action<B> secondAction = second.reader.apply(questReader);
            return frame -> firstAction.run(frame)
                    .thenCompose(first -> secondAction.run(frame).thenApply(value -> function.apply(first, value)));
        });
    }

    public <B, C, R> Parser<R> fold(Parser<B> second, Parser<C> third, Function3<T, B, C, R> function) {
        return fold(second, Pair::new).fold(third,
                (pair, value) -> function.apply(pair.first, pair.second, value));
    }

    public <B, C, D, R> Parser<R> fold(
            Parser<B> second, Parser<C> third, Parser<D> fourth, Function4<T, B, C, D, R> function) {
        // 要是有 for 表达式和高阶类型就好了……
        return fold(second, third, Triple::new).fold(fourth,
                (values, value) -> function.apply(values.first, values.second, values.third, value));
    }

    public <B, C, D, E, R> Parser<R> fold(
            Parser<B> second,
            Parser<C> third,
            Parser<D> fourth,
            Parser<E> fifth,
            Function5<T, B, C, D, E, R> function) {
        return fold(second, third, fourth, Quadruple::new).fold(fifth,
                (values, value) -> function.apply(
                        values.first, values.second, values.third, values.fourth, value));
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
        return new QuestActionParser() {
            @Override
            public <R> QuestAction<R> resolve(QuestReader resolver) {
                Action<Action<A>> outer = parser.reader.apply(resolver);
                return new QuestAction<R>() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public CompletableFuture<R> process(QuestContext.Frame frame) {
                        return outer.run(frame).thenCompose(action ->
                                (CompletableFuture<R>) action.run(frame));
                    }
                };
            }
        };
    }

    /** 作为 Parser.create 所用入口而保留的小型门面。 */
    public enum Instance {
        INSTANCE;
        public <T> Parser<T> point(T value) { return Parser.point(value); }
        public <T> Parser<T> of(Function<QuestReader, T> parser) { return Parser.of(parser); }
        public <T> Parser<T> frame(Function<QuestReader, Action<T>> parser) { return Parser.frame(parser); }
    }

    private static final class Pair<A, B> {
        private final A first;
        private final B second;
        private Pair(A first, B second) { this.first = first; this.second = second; }
    }

    private static final class Triple<A, B, C> {
        private final A first;
        private final B second;
        private final C third;
        private Triple(A first, B second, C third) { this.first = first; this.second = second; this.third = third; }
    }

    private static final class Quadruple<A, B, C, D> {
        private final A first;
        private final B second;
        private final C third;
        private final D fourth;
        private Quadruple(A first, B second, C third, D fourth) {
            this.first = first; this.second = second; this.third = third; this.fourth = fourth;
        }
    }
}
