package me.kzheart.klib.scope;

/**
 * 表示更改作用域生命周期时遇到的一个或多个失败。
 */
public final class ScopeLifecycleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ScopeLifecycleException(String message) {
        super(message);
    }

    ScopeLifecycleException(String message, Throwable cause) {
        super(message, cause);
    }

    void addFailure(Throwable failure) {
        addSuppressed(failure);
    }
}
