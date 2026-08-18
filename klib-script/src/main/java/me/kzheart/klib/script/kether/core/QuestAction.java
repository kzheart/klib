/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

import java.util.concurrent.CompletableFuture;

/** 一个可异步执行的 Kether 动作。 */
public abstract class QuestAction<T> {

    /**
     * 不应直接调用此方法，请参阅 {@link QuestContext.Frame#newFrame(ParsedAction)}。
     */
    public abstract CompletableFuture<T> process(QuestContext.Frame frame);

    public static <T> QuestAction<T> noop() {
        return new QuestAction<T>() {
            @Override
            public CompletableFuture<T> process(QuestContext.Frame frame) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public String toString() {
                return "NoOp{}";
            }
        };
    }
}
