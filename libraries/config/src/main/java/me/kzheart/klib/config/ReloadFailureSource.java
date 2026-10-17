package me.kzheart.klib.config;

import java.util.Optional;
import java.util.function.Consumer;
import me.kzheart.klib.scope.Disposable;

/** 可重新加载配置持有者共享的公共失败观测契约。 */
public interface ReloadFailureSource {
    Disposable onReloadFailure(Consumer<? super Throwable> listener);

    Optional<Throwable> lastReloadFailure();
}
