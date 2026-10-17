package me.kzheart.klib.component

import me.kzheart.klib.command.api.Commands
import me.kzheart.klib.config.api.Configs
import me.kzheart.klib.event.Events
import me.kzheart.klib.scheduler.Tasks

abstract class KComponent {
    private var context: KContext? = null
    @JvmName("attach") internal fun attach(value: KContext) {
        check(context == null) { "Component instance already installed" }
        context = value
    }
    protected abstract fun setup()
    fun context(): KContext = context?.takeUnless { it.scope().isClosed }
        ?: throw IllegalStateException("Component is not active")
    protected fun commands(): Commands = context().commands()
    protected fun configs(): Configs = context().configs()
    protected fun events(): Events = context().events()
    protected fun tasks(): Tasks = context().tasks()
    protected fun components(): Components = context().components()
    internal fun initialize() = setup()
}
