package me.kzheart.klib.scope;

/**
 * 生命周期由 {@link Scope} 管理的资源。
 */
@FunctionalInterface
public interface Disposable {

    /**
     * 释放此资源。
     *
     * 释放失败时，实现可以抛出运行时异常。所属作用域仍会继续释放后续资源，
     * 并汇总所有失败。
     */
    void dispose();
}
