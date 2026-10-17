package me.kzheart.klib.config.api;

import me.kzheart.klib.scope.Disposable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public interface ConfigDocument<T> extends Disposable {
    String sourceName();

    T value();

    void reload();

    /** 重新加载配置，并在已注册的变更监听器全部执行完毕后完成。 */
    default CompletionStage<Void> reloadAsync() {
        reload();
        return CompletableFuture.completedFuture(null);
    }

    Disposable onChange(Runnable listener);
}
