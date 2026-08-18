package me.kzheart.klib.hook;

import java.util.Objects;
import me.kzheart.klib.scope.Disposable;

/** 可选集成工厂使用的默认钩子实现。 */
public final class ResolvedHook<T> implements Hook<T> {

    private final String dependency;
    private final T value;
    private final DependencyStatus status;
    private final String detail;
    private final Disposable disposal;
    private boolean disposed;

    public ResolvedHook(
            String dependency,
            T value,
            DependencyStatus status,
            String detail,
            Disposable disposal
    ) {
        this.dependency = Texts.requireText(dependency, "dependency");
        this.value = Objects.requireNonNull(value, "value");
        this.status = Objects.requireNonNull(status, "status");
        this.detail = Texts.requireText(detail, "detail");
        this.disposal = Objects.requireNonNull(disposal, "disposal");
    }

    @Override
    public String dependency() {
        return dependency;
    }

    @Override
    public boolean available() {
        return status == DependencyStatus.AVAILABLE;
    }

    @Override
    public T value() {
        return value;
    }

    @Override
    public DependencyStatus status() {
        return status;
    }

    @Override
    public String detail() {
        return detail;
    }

    @Override
    public synchronized void dispose() {
        if (!disposed) {
            disposed = true;
            disposal.dispose();
        }
    }
}
