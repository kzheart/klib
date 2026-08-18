package me.kzheart.klib.scheduler;

import me.kzheart.klib.scope.Disposable;

public interface TaskHandle extends Disposable {
    boolean cancel();

    boolean isCancelled();

    boolean isDone();

    @Override
    default void dispose() {
        cancel();
    }
}
