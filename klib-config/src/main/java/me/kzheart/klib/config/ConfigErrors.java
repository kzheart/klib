package me.kzheart.klib.config;

import java.util.Objects;
import java.util.function.Consumer;
import me.kzheart.klib.config.api.ConfigDocument;
import me.kzheart.klib.scope.Disposable;

/** 观测 Scope.config 返回文档错误的标准入口。 */
public final class ConfigErrors {
    private ConfigErrors() {
    }

    public static Disposable onReloadFailure(
            ConfigDocument<?> document,
            Consumer<? super Throwable> listener
    ) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(listener, "listener");
        if (!(document instanceof ReloadFailureSource)) {
            throw new IllegalArgumentException(
                    "Config document does not expose reload failures: "
                            + document.getClass().getName());
        }
        return ((ReloadFailureSource) document).onReloadFailure(listener);
    }
}
