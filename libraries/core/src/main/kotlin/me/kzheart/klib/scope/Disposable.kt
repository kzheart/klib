package me.kzheart.klib.scope

/** A scope-owned resource; failures are aggregated without stopping later cleanup. */
@java.lang.FunctionalInterface
fun interface Disposable {
    fun dispose()
}
