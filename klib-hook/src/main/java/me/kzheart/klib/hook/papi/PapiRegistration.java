package me.kzheart.klib.hook.papi;

import java.util.Objects;
import me.kzheart.klib.scope.Disposable;

/** 单个 PlaceholderAPI 扩展注册的幂等句柄。 */
public final class PapiRegistration implements Disposable {

    private final String identifier;
    private final boolean available;
    private final Disposable delegate;
    private boolean disposed;

    PapiRegistration(String identifier, boolean available, Disposable delegate) {
        this.identifier = identifier;
        this.available = available;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public String identifier() {
        return identifier;
    }

    /** 该句柄是否代表真正的 PlaceholderAPI 注册。 */
    public boolean isAvailable() {
        return available;
    }

    public synchronized boolean isDisposed() {
        return disposed;
    }

    @Override
    public synchronized void dispose() {
        if (!disposed) {
            disposed = true;
            delegate.dispose();
        }
    }
}
