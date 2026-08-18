/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** 存储在 Kether 变量表中的延迟动作。 */
public final class QuestFuture<T> {
    private final ParsedAction<T> action;
    private CompletableFuture<T> future;

    public QuestFuture(ParsedAction<T> action) { this(action, null); }
    public QuestFuture(ParsedAction<T> action, CompletableFuture<T> future) {
        this.action = action;
        this.future = future;
    }
    public ParsedAction<T> getAction() { return action; }
    public CompletableFuture<T> getFuture() { return future; }

    public void run(QuestContext.Frame frame) {
        checkState(future == null, "already running");
        future = frame.newFrame(action).run();
    }

    public void close() {
        checkState(future != null, "not running");
        future = null;
    }

    @SuppressWarnings("unchecked")
    public static <T> Consumer<T> complete(CompletableFuture<T> future) {
        return value -> {
            if (value instanceof QuestFuture) {
                ((QuestFuture<T>) value).getFuture().thenAccept(future::complete);
            } else {
                future.complete(value);
            }
        };
    }

    private static void checkState(boolean state, String message) {
        if (!state) throw new IllegalStateException(message);
    }
}
