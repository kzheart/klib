package me.kzheart.klib.hook.papi

import me.kzheart.klib.scope.Disposable

/** 单个 PlaceholderAPI 扩展注册的幂等句柄。 */
class PapiRegistration internal constructor(private val identifier: String, private val available: Boolean,
                                            private val delegate: Disposable) : Disposable {
    private var disposed = false
    fun identifier(): String = identifier
    fun isAvailable(): Boolean = available
    @Synchronized fun isDisposed(): Boolean = disposed
    @Synchronized override fun dispose() {
        if (!disposed) {
            disposed = true
            delegate.dispose()
        }
    }
}
