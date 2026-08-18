/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

import java.util.function.Function;

/** 从任务读取器中解析一次动作调用。 */
@FunctionalInterface
public interface QuestActionParser {

    <T> QuestAction<T> resolve(QuestReader resolver);

    static <T> QuestActionParser of(Function<QuestReader, QuestAction<T>> function) {
        return new QuestActionParser() {
            @Override
            @SuppressWarnings("unchecked")
            public <A> QuestAction<A> resolve(QuestReader resolver) {
                return (QuestAction<A>) function.apply(resolver);
            }
        };
    }
}
