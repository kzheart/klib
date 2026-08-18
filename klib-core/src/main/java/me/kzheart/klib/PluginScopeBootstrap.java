package me.kzheart.klib;

import java.util.function.Consumer;
import me.kzheart.klib.scope.Scope;
import me.kzheart.klib.scope.ScopeImpl;

final class PluginScopeBootstrap {
    private PluginScopeBootstrap() {
    }

    static ScopeImpl create(String name, Consumer<? super Scope> setup) {
        return ScopeImpl.create(name, setup);
    }

    /**
     * 重建作用域；失败时由处理器统一报告，且不再向外抛出异常。
     *
     * @return 是否重建成功
     */
    static boolean rebuild(Scope scope, Consumer<? super Throwable> failureHandler) {
        try {
            scope.rebuild();
            return true;
        } catch (RuntimeException failure) {
            failureHandler.accept(failure);
            return false;
        } catch (Error failure) {
            failureHandler.accept(failure);
            throw failure;
        }
    }
}
