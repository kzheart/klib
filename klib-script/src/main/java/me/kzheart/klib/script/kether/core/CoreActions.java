/*
 * 上游 Java SimpleReader 所引用的两个 Kotlin 动作的 Java 替代实现。
 * Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6
 */
package me.kzheart.klib.script.kether.core;

import java.util.concurrent.CompletableFuture;

final class CoreActions {

    private CoreActions() {
    }

    static final class Literal<T> extends QuestAction<T> {
        private final T value;
        private final boolean misspelled;

        Literal(T value) {
            this(value, false);
        }

        Literal(T value, boolean misspelled) {
            this.value = value;
            this.misspelled = misspelled;
        }

        @Override
        public CompletableFuture<T> process(QuestContext.Frame frame) {
            return CompletableFuture.completedFuture(value);
        }

        T getValue() { return value; }
        boolean isMisspelled() { return misspelled; }
        @Override public String toString() { return "Literal{" + value + '}'; }
    }

    static final class Get<T> extends QuestAction<T> {
        private final String name;

        Get(String name) {
            this.name = name;
        }

        @Override
        public CompletableFuture<T> process(QuestContext.Frame frame) {
            return CompletableFuture.completedFuture(frame.variables().getOrNull(name));
        }

        @Override public String toString() { return "Get{" + name + '}'; }
    }
}
