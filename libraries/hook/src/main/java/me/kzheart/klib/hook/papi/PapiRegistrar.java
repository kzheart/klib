package me.kzheart.klib.hook.papi;

import me.kzheart.klib.scope.Disposable;

/** 面向 PlaceholderAPI 的集成所实现的运行时桥接。 */
@FunctionalInterface
public interface PapiRegistrar {

    Disposable register(String identifier, PapiExpansion expansion);

    default boolean available() {
        return true;
    }
}
