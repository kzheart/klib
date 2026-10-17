package me.kzheart.klib.guard

import me.kzheart.klib.KLogger
import me.kzheart.klib.event.KEventDispatcher
import me.kzheart.klib.scheduler.BukkitSchedulerFactory
import me.kzheart.klib.scheduler.SchedulerFactory
import me.kzheart.klib.scope.Scope
import me.kzheart.klib.scope.ScopeImpl

/** One lifecycle state holder; the public Java entry points retain their original monitor and hooks. */
internal class KlibCloudPluginLifecycle(private val owner: KlibCloudPlugin) {
    private var host: PluginHost? = null
    private var logger: KLogger? = null
    private var rootScope: ScopeImpl? = null

    fun load(value: PluginHost?) {
        check(value != null && host == null) { "invalid lifecycle" }
        host = value
        logger = KLogger(value.logger())
        try { owner.load() }
        catch (failure: Exception) { resetHost(); throw failure }
        catch (failure: LinkageError) { resetHost(); throw failure }
    }
    fun enable() {
        check(host != null && rootScope == null) { "invalid lifecycle" }
        val currentHost = host!!
        val currentLogger = logger!!
        rootScope = ScopeImpl.create(currentHost.productId()) { root ->
            val events = root.install(KEventDispatcher(currentHost.plugin(), currentHost.server().pluginManager, currentLogger))
            root.registerCapability(KLogger::class.java, currentLogger)
            root.registerCapability(KEventDispatcher::class.java, events)
            root.registerCapability(SchedulerFactory::class.java,
                BukkitSchedulerFactory(currentHost.plugin(), currentHost.server().scheduler, currentLogger))
            owner.setup(root)
        }
        currentLogger.success("云端插件已启用: ${currentHost.productId()}")
    }
    fun disable() {
        val current = rootScope
        rootScope = null
        var closeFailure: RuntimeException? = null
        try { current?.close() } catch (failure: RuntimeException) { closeFailure = failure }
        try { if (host != null) owner.disable() } finally { resetHost() }
        closeFailure?.let { throw it }
    }
    fun host(): PluginHost = host ?: throw IllegalStateException("cloud plugin is not loaded")
    fun logger(): KLogger = logger ?: throw IllegalStateException("cloud plugin is not loaded")
    fun rootScope(): Scope = rootScope?.takeUnless { it.isClosed } ?: throw IllegalStateException("cloud plugin scope is not active")
    private fun resetHost() { host = null; logger = null }
}
