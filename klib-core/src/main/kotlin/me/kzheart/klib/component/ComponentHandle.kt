package me.kzheart.klib.component

import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope

class ComponentHandle internal constructor(private val owner: Scope) : Disposable, AutoCloseable {
    fun isClosed(): Boolean = owner.isClosed
    override fun close() = owner.close()
    override fun dispose() = close()
}
