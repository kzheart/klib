package me.kzheart.klib.ui

import java.util.Objects
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BiFunction
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.UnaryOperator
import me.kzheart.klib.scheduler.AsyncTasks
import me.kzheart.klib.scheduler.Ticks
import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/** 一次打开的不可变状态与只读异步观察；业务操作的所有权始终属于调用方。 */
class ReactiveMenuSession<S> internal constructor(private val viewer: Player, initialState: S,
                                                  private val view: Function<in ReactiveMenuSession<S>, MenuModel>) {
    private val refreshPending = AtomicBoolean()
    private val reads = ConcurrentHashMap<String, Read>()
    @Volatile private var holder: MenuHolder? = null
    @Volatile private var closed = false
    private var state: S = Objects.requireNonNull(initialState, "initialState")
    private var revision = 0L
    private var rendering = false
    @Volatile private var renderingThread: Thread? = null
    private var updating = false
    private var navigationPending = false

    @JvmName("attach") internal fun attach(attached: MenuHolder) {
        check(holder == null) { "reactive session is already attached" }
        holder = attached
        attached.session().scope().install(Disposable { release() })
    }
    @JvmName("buildModel") internal fun buildModel(): MenuModel {
        MenuRenderer.ensureMainThread("构建状态菜单")
        check(!rendering) { "recursive menu render" }
        rendering = true; renderingThread = Thread.currentThread()
        try { return Objects.requireNonNull(view.apply(this), "menu view returned null") }
        finally { renderingThread = null; rendering = false }
    }
    fun viewer(): Player { MenuRenderer.ensureMainThread("读取菜单玩家"); return viewer }
    fun state(): S {
        MenuRenderer.ensureMainThread("读取菜单状态")
        check(!closed) { "reactive menu session is closed" }
        return state
    }
    fun holder(): MenuHolder { MenuRenderer.ensureMainThread("读取菜单会话"); return requireAttached() }
    fun scope(): Scope = holder().session().scope()
    fun update(update: UnaryOperator<S>) {
        requireMutable()
        updating = true
        val replacement = try { Objects.requireNonNull(update.apply(state), "state update returned null") }
            finally { updating = false }
        if (state == replacement) return
        state = replacement; revision++
        discardReads(); invalidate()
    }

    /** 任意线程通知合并到下一个 tick；关闭后忽略，视图内禁止递归通知。 */
    fun invalidate() {
        check(Thread.currentThread() !== renderingThread) { "menu views must not invalidate themselves" }
        val current = holder ?: throw IllegalStateException("menu view must not invalidate while opening")
        if (closed || current.session().isClosed() || !refreshPending.compareAndSet(false, true)) return
        try {
            current.session().scope().after(Ticks.of(1)) {
                refreshPending.set(false)
                if (isCurrent()) {
                    try { current.refresh(buildModel()) }
                    catch (failure: RuntimeException) { failRender(current, failure); throw failure }
                    catch (failure: Error) { failRender(current, failure); throw failure }
                }
            }
        } catch (failure: RuntimeException) {
            refreshPending.set(false)
            if (!closed && !current.session().isClosed()) throw failure
        }
    }
    private fun failRender(current: MenuHolder, failure: Throwable) {
        if (!current.session().isClosed()) current.renderer.failRender(current, failure)
    }
    fun <D : Disposable> bind(subscription: D): D {
        try { requireMutable(); return scope().install(subscription) }
        catch (failure: RuntimeException) {
            try { subscription.dispose() } catch (cleanup: RuntimeException) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /** 只把同状态、同打开实例的最新读取交回主线程；取消观察从不取消 source。 */
    fun <T> readLatest(key: String, source: CompletionStage<T>, reducer: BiFunction<in S, in T, out S>,
                       onFailure: Consumer<in Throwable>): CompletionStage<Boolean> {
        requireMutable()
        require(key.trim().isNotEmpty()) { "read key must not be blank" }
        val read = Read(revision)
        read.result.whenComplete { _, _ -> reads.remove(key, read); read.releaseDelivery() }
        reads.put(key, read)?.discard()
        try {
            val outcome = source.handle { value, failure -> Outcome(value, failure) }
            val delivery = scope().submitSync(outcome) { completed ->
                if (read.result.isDone || !isCurrent() || revision != read.revision || !reads.remove(key, read)) false
                else {
                    if (completed.failure != null) {
                        val failure = AsyncTasks.unwrap(completed.failure)
                        try { onFailure.accept(failure) }
                        catch (callbackFailure: Throwable) { if (callbackFailure !== failure) failure.addSuppressed(callbackFailure) }
                        throw CompletionException(failure)
                    }
                    update(UnaryOperator { current -> reducer.apply(current, completed.value) })
                    true
                }
            }
            read.attachDelivery(delivery)
            delivery.whenComplete { applied, failure ->
                reads.remove(key, read)
                when {
                    failure == null -> read.result.complete(applied)
                    closed || holder!!.session().isClosed() || AsyncTasks.unwrap(failure) is CancellationException || read.result.isDone -> read.result.complete(false)
                    else -> read.result.completeExceptionally(AsyncTasks.unwrap(failure))
                }
            }
        } catch (failure: RuntimeException) { reads.remove(key, read); read.result.completeExceptionally(failure) }
        return read.result
    }
    fun navigate(navigation: Runnable): Boolean {
        requireMutable()
        if (navigationPending) return false
        navigationPending = true
        discardReads()
        val expected = revision
        try { scope().after(Ticks.of(1)) { navigationPending = false; if (isCurrent() && revision == expected) navigation.run() } }
        catch (failure: RuntimeException) { navigationPending = false; throw failure }
        return true
    }
    fun isCurrent(): Boolean {
        MenuRenderer.ensureMainThread("检查菜单会话")
        val current = holder
        return !closed && current != null && !current.session().isClosed() && viewer.isOnline &&
            (Bukkit.getServer() == null || Bukkit.getPlayer(viewer.uniqueId) === viewer) &&
            viewer.openInventory.topInventory.holder === current
    }
    private fun requireAttached(): MenuHolder = holder ?: throw IllegalStateException("menu view cannot use an unattached session")
    private fun requireMutable() {
        MenuRenderer.ensureMainThread("修改菜单状态")
        check(!rendering && !updating) { "menu views and state reducers must be side-effect free" }
        requireAttached()
        check(isCurrent()) { "reactive menu session is no longer current" }
    }
    private fun release() { closed = true; refreshPending.set(false); discardReads() }
    private fun discardReads() { val discarded = reads.values.toList(); reads.clear(); discarded.forEach { it.discard() } }
    private class Read(val revision: Long) {
        val result = CompletableFuture<Boolean>()
        private val delivery = AtomicReference<CompletableFuture<*>?>()
        fun attachDelivery(task: CompletableFuture<*>) { delivery.set(task); if (result.isDone) releaseDelivery() }
        fun releaseDelivery() { delivery.getAndSet(null)?.cancel(false) }
        fun discard() { result.complete(false) }
    }
    private class Outcome<T>(val value: T, val failure: Throwable?)
}
