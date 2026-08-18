package me.kzheart.klib.config;

import java.util.concurrent.CopyOnWriteArrayList;
import me.kzheart.klib.scope.Disposable;

/** 可重新加载持有者共用的监听器注册与快照迭代实现。 */
final class ListenerList {
    private final CopyOnWriteArrayList<Runnable> listeners =
            new CopyOnWriteArrayList<Runnable>();

    void add(Runnable listener) {
        listeners.add(listener);
    }

    Disposable registration(Runnable listener) {
        return new Registration(listeners, listener);
    }

    void clear() {
        listeners.clear();
    }

    Iterable<Runnable> snapshot() {
        return listeners;
    }

    private static final class Registration implements Disposable {
        private final CopyOnWriteArrayList<Runnable> listeners;
        private final Runnable listener;
        private boolean disposed;

        private Registration(CopyOnWriteArrayList<Runnable> listeners, Runnable listener) {
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
