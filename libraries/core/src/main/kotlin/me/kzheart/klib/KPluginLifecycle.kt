package me.kzheart.klib

import me.kzheart.klib.command.api.Commands
import me.kzheart.klib.component.Components
import me.kzheart.klib.component.KContext
import me.kzheart.klib.config.api.Configs
import me.kzheart.klib.event.Events
import me.kzheart.klib.event.KEventDispatcher
import me.kzheart.klib.scheduler.BukkitSchedulerFactory
import me.kzheart.klib.scheduler.SchedulerFactory
import me.kzheart.klib.scheduler.Tasks
import me.kzheart.klib.scope.Scope
import me.kzheart.klib.scope.ScopeImpl
import java.util.function.Consumer

/** Plugin lifecycle and its capability graph share one generation-owned scope. */
internal class KPluginLifecycle(private val plugin: KPlugin, private val configure: Consumer<Scope>) {
    @Volatile private var rootScope: ScopeImpl? = null
    private var klibLogger: KLogger? = null
    @Volatile private var context: KContext? = null

    fun context(): KContext = context?.takeUnless { it.scope().isClosed }
        ?: throw IllegalStateException("Plugin context is not active")
    fun enable() {
        val active = activeInstance
        if (active != null && active !== plugin) {
            plugin.logger.severe("检测到另一个已激活的 KPlugin 实例（${describe(active)}），本插件将被禁用：" +
                "同一服务端只允许存在一份未重定位的 klib。" +
                "请使用 me.kzheart.klib Gradle 插件打包，把 klib 重定位到插件自己的包名下。")
            plugin.server.pluginManager.disablePlugin(plugin)
            return
        }
        activeInstance = plugin
        val log = KLogger(plugin.logger)
        klibLogger = log
        try {
            rootScope = PluginScopeBootstrap.create(plugin.name) { root ->
                context = KContext(root)
                installCoreCapabilities(root)
                configure.accept(root)
            }
            log.success("插件已启用")
        } catch (failure: Throwable) {
            activeInstance = null
            log.error("插件初始化失败，已清理部分初始化资源", failure)
            plugin.server.pluginManager.disablePlugin(plugin)
        }
    }

    fun disable() {
        val current = rootScope
        rootScope = null
        try { current?.close() } catch (failure: RuntimeException) {
            logger().error("插件关闭时有资源释放失败", failure)
        }
        if (activeInstance === plugin) activeInstance = null
    }

    fun rootScope(): Scope = rootScope?.takeUnless { it.isClosed }
        ?: throw IllegalStateException("Plugin scope is not active: available only after onEnable" +
            " succeeded and before onDisable closes it")
    fun rebuild(): Boolean = PluginScopeBootstrap.rebuild(rootScope(), ::failRebuild)
    fun logger(): KLogger = klibLogger ?: throw IllegalStateException(
        "Plugin logger is not initialized: available only from onEnable onwards, not from the constructor or onLoad")

    private fun installCoreCapabilities(root: Scope) {
        val events = root.install(KEventDispatcher(plugin, plugin.server.pluginManager, logger()))
        root.registerCapability(KLogger::class.java, logger())
        root.registerCapability(KEventDispatcher::class.java, events)
        root.registerCapability(SchedulerFactory::class.java, BukkitSchedulerFactory(plugin, plugin.server.scheduler, logger()))
    }
    private fun failRebuild(failure: Throwable) {
        logger().error("插件重载失败，已关闭残留资源并禁用插件", failure)
        try { rootScope?.close() } catch (cleanup: RuntimeException) { failure.addSuppressed(cleanup) }
        plugin.server.pluginManager.disablePlugin(plugin)
    }
    companion object {
        @Volatile private var activeInstance: KPlugin? = null
        @JvmStatic fun instance(): KPlugin = activeInstance ?: throw IllegalStateException(
            "No active KPlugin instance: available only after a KPlugin has finished onEnable and before it is disabled")
        private fun describe(other: KPlugin): String = try {
            other.name.takeUnless { it.isNullOrEmpty() } ?: other.javaClass.name
        } catch (_: RuntimeException) { other.javaClass.name }
        catch (_: LinkageError) { other.javaClass.name }
    }
}
