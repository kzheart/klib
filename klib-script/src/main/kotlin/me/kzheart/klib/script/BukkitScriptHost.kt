package me.kzheart.klib.script

import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Objective
import org.bukkit.scoreboard.Scoreboard
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.function.BiConsumer
import java.util.function.Supplier

/** 作用域持有的宿主。共享服务边界统一检查主线程及关闭状态，apply 本身不访问 Bukkit。 */
class BukkitScriptHost private constructor(options: Builder) : Disposable, AutoCloseable, Listener {
    private val plugin = options.plugin
    private val scope = options.scope
    private val javascriptFactory = options.javascriptFactory
    private val services: List<(ScriptContext.Builder) -> Unit>
    private val installationLock = Any()
    private val sidebars = HashMap<UUID, Sidebar>()
    private val pendingDelays = HashMap<CompletableFuture<Any?>, BukkitTask>()
    private val delayCleanupLock = Any()
    private val delayCleanups = HashSet<BukkitTask>()
    private var javascript: JavaScriptEvaluator? = null
    @Volatile private var closed = false

    init {
        checkThread()
        val installers = ArrayList<(ScriptContext.Builder) -> Unit>()
        val scoreboard = if (options.scoreboardEnabled) BiConsumer<Player, List<String>?>(::scoreboard) else options.scoreboardCallback
        val defaults = BukkitScriptServices.apply(ScriptContext.builder(), plugin, scoreboard, false).build()
        cache(installers, defaults, MessageSink::class.java)
        cache(installers, defaults, CommandSink::class.java)
        cache(installers, defaults, ScriptSenderQuery::class.java)
        cache(installers, defaults, PlayerQuery::class.java)
        val delayScheduler = DelayScheduler(::delay)
        installers.add { it.service(DelayScheduler::class.java, delayScheduler) }
        cache(installers, defaults, ScriptLogger::class.java)
        cache(installers, defaults, ScriptPlatform::class.java)
        cache(installers, defaults, ScriptPropertyAccess::class.java)
        if (javascriptFactory != null) {
            val evaluator = JavaScriptEvaluator(::evaluate)
            installers.add { it.service(JavaScriptEvaluator::class.java, evaluator) }
        }
        services = installers.toList()
        Bukkit.getPluginManager().registerEvents(this, plugin)
        try { scope.install(this) } catch (failure: RuntimeException) { dispose(); throw failure }
    }
    fun apply(builder: ScriptContext.Builder): ScriptContext.Builder {
        synchronized(installationLock) {
            check(!closed) { "Bukkit script host services are closed" }
            services.forEach { it(builder) }
        }
        return builder
    }
    private fun <T : Any> cache(installers: MutableList<(ScriptContext.Builder) -> Unit>, defaults: ScriptContext, type: Class<T>) {
        val delegate = defaults.requireService(type)
        val guarded = type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, arguments ->
            invokeService(proxy, delegate, method, arguments)
        })
        installers.add { it.service(type, guarded) }
    }
    private fun invokeService(proxy: Any, delegate: Any, method: Method, arguments: Array<Any?>?): Any? {
        if (method.declaringClass == Any::class.java) return when (method.name) {
            "equals" -> proxy === arguments!![0]
            "hashCode" -> System.identityHashCode(proxy)
            else -> "BukkitScriptHost service"
        }
        checkActive()
        try { return method.invoke(delegate, *(arguments ?: emptyArray())) }
        catch (failure: InvocationTargetException) { throw failure.cause!! }
    }
    private fun evaluate(source: String, bindings: Map<String, Any?>): Any? {
        checkActive()
        val evaluator = javascript ?: try {
            val created: JavaScriptEvaluator? = javascriptFactory!!.get()
            (created ?: throw NullPointerException("JavaScript evaluator factory returned null")).also { javascript = it }
        } catch (failure: RuntimeException) { throw evaluatorFailure(failure) }
        catch (failure: LinkageError) { throw evaluatorFailure(failure) }
        return evaluator.eval(source, bindings)
    }
    private fun evaluatorFailure(failure: Throwable): IllegalStateException = IllegalStateException(
        "JavaScript evaluator could not start: ${failure.message}. Check the configured engine/provider and its runtime dependencies.", failure)
    private fun delay(duration: Duration): CompletionStage<Any?> {
        checkActive()
        val ticks = maxOf(0L, duration.toMillis() / 50L)
        val future = CompletableFuture<Any?>()
        val task = Bukkit.getScheduler().runTaskLater(plugin, Runnable { pendingDelays.remove(future); future.complete(null) }, ticks)
        pendingDelays[future] = task
        future.whenComplete { _, _ ->
            if (!closed) {
                if (Bukkit.isPrimaryThread()) cancelDelay(future) else scheduleDelayCleanup(future)
            }
        }
        return future
    }
    private fun cancelDelay(future: CompletableFuture<Any?>) { checkThread(); pendingDelays.remove(future)?.cancel() }
    private fun scheduleDelayCleanup(future: CompletableFuture<Any?>) {
        synchronized(delayCleanupLock) {
            if (closed) return
            val cleanup = arrayOfNulls<BukkitTask>(1)
            cleanup[0] = Bukkit.getScheduler().runTask(plugin, Runnable {
                synchronized(delayCleanupLock) { delayCleanups.remove(cleanup[0]) }
                if (!closed) cancelDelay(future)
            })
            delayCleanups.add(cleanup[0]!!)
        }
    }

    private fun scoreboard(player: Player, lines: List<String>?) {
        checkActive()
        if (lines.isNullOrEmpty()) { remove(player); return }
        require(lines.size <= MAX_LINES + 1) { "scoreboard supports a title and at most 15 sidebar lines" }
        val checked: List<String?> = lines
        for (line in checked) if (line == null) throw NullPointerException("scoreboard lines must not be null")
        val sidebar = sidebars[player.uniqueId]
        if (sidebar == null || player.scoreboard !== sidebar.board) {
            if (sidebar != null) { sidebars.remove(player.uniqueId); sidebar.discard() }
            val previous = player.scoreboard
            val board = (Bukkit.getScoreboardManager() ?: throw NullPointerException("Bukkit scoreboard manager is unavailable")).newScoreboard
            val objective = board.registerNewObjective("klib_script", "dummy")
            objective.displaySlot = DisplaySlot.SIDEBAR
            val created = Sidebar(previous, board, objective)
            try { created.render(lines); player.scoreboard = board; sidebars[player.uniqueId] = created }
            catch (failure: RuntimeException) { created.discard(); throw failure }
        } else sidebar.render(lines)
    }
    private fun remove(player: Player) {
        val sidebar = sidebars.remove(player.uniqueId) ?: return
        try { if (player.scoreboard === sidebar.board) player.scoreboard = sidebar.previous }
        finally { sidebar.discard() }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) { checkThread(); if (!closed) remove(event.player) }
    @EventHandler(priority = EventPriority.MONITOR)
    fun onDisable(event: PluginDisableEvent) { if (event.plugin === plugin) dispose() }
    private fun checkActive() { checkThread(); check(!closed) { "Bukkit script host services are closed" } }

    /** 所有已创建资源只释放一次；单个清理失败不会跳过其他资源。 */
    override fun dispose() {
        checkThread()
        synchronized(installationLock) { if (closed) return; closed = true }
        val cleanups = synchronized(delayCleanupLock) { delayCleanups.toList().also { delayCleanups.clear() } }
        val failures = CloseFailures()
        try {
            cleanups.forEach { failures.capture { it.cancel() } }
            for ((future, task) in pendingDelays.toMap()) {
                pendingDelays.remove(future)
                try { failures.capture { task.cancel() } } finally { future.cancel(false) }
            }
            for ((id, sidebar) in sidebars.toMap()) failures.capture {
                val player = Bukkit.getPlayer(id)
                if (player != null) remove(player) else sidebar.discard()
            }
            try {
                val evaluator = javascript
                if (evaluator is Disposable) evaluator.dispose() else if (evaluator is AutoCloseable) evaluator.close()
            } catch (error: Exception) { failures.add(IllegalStateException("Failed to close the JavaScript evaluator", error)) }
        } finally {
            sidebars.clear(); pendingDelays.clear(); javascript = null
            HandlerList.unregisterAll(this)
            scope.remove(this)
        }
        failures.throwIfPresent()
    }
    override fun close() = dispose()

    class Builder internal constructor(internal val plugin: Plugin, internal val scope: Scope) {
        internal var scoreboardEnabled = false
        internal var scoreboardCallback: BiConsumer<Player, List<String>?>? = null
        internal var javascriptFactory: Supplier<out JavaScriptEvaluator>? = null
        fun scoreboard(enabled: Boolean): Builder = apply { scoreboardEnabled = enabled; scoreboardCallback = null }
        fun scoreboard(callback: BiConsumer<Player, List<String>?>): Builder = apply { scoreboardEnabled = false; scoreboardCallback = callback }
        fun javascript(factory: Supplier<out JavaScriptEvaluator>): Builder = apply { javascriptFactory = factory }
        fun javascriptEngine(engineName: String): Builder = apply { javascriptFactory = ScriptJavaScriptEngines.named(engineName, plugin.javaClass.classLoader) }
        fun build(): BukkitScriptHost = BukkitScriptHost(this)
    }
    private class Sidebar(val previous: Scoreboard, val board: Scoreboard, private val objective: Objective) {
        fun render(lines: List<String>) {
            objective.displayName = lines[0]
            board.entries.toList().forEach { board.resetScores(it) }
            board.teams.toList().forEach { it.unregister() }
            for (index in 1 until lines.size) {
                val entry = "§${Integer.toHexString(index - 1)}§r"
                val team = board.registerNewTeam("line$index")
                team.prefix = lines[index]; team.addEntry(entry)
                objective.getScore(entry).score = lines.size - index
            }
        }
        fun discard() { board.teams.toList().forEach { it.unregister() }; if (objective.scoreboard != null) objective.unregister() }
    }
    private class CloseFailures {
        private var failure: RuntimeException? = null
        fun capture(cleanup: () -> Unit) { try { cleanup() } catch (error: RuntimeException) { add(error) } }
        fun add(error: RuntimeException) { val current = failure; if (current == null) failure = error else current.addSuppressed(error) }
        fun throwIfPresent() { failure?.let { throw it } }
    }
    companion object {
        private const val MAX_LINES = 15
        @JvmStatic fun builder(plugin: Plugin, scope: Scope): Builder = Builder(plugin, scope)
        private fun checkThread() { check(Bukkit.isPrimaryThread()) { "Bukkit script host services must run on the server main thread" } }
    }
}
