package me.kzheart.klib.config;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import me.kzheart.klib.scope.Disposable;

/** 重新加载失败的监听器存储；监听器自身失败不会掩盖原始失败。 */
final class FailureListenerList {
    private final CopyOnWriteArrayList<Consumer<? super Throwable>> listeners =
            new CopyOnWriteArrayList<Consumer<? super Throwable>>();

    Disposable add(Consumer<? super Throwable> listener) {
        listeners.add(listener);
        return new Registration(listeners, listener);
    }

    Iterable<Consumer<? super Throwable>> snapshot() {
        return listeners;
    }

    void clear() {
        listeners.clear();
    }

    private static final class Registration implements Disposable {
        private final CopyOnWriteArrayList<Consumer<? super Throwable>> listeners;
        private final Consumer<? super Throwable> listener;
        private boolean disposed;

        private Registration(
                CopyOnWriteArrayList<Consumer<? super Throwable>> listeners,
                Consumer<? super Throwable> listener
        ) {
            this.listeners = listeners;
            this.listener = listener;
        }

        @Override
        public synchronized void dispose() {
            if (!disposed) {
                disposed = true;
                listeners.remove(listener);
            }
        }
    }
}
