package me.kzheart.klib.ui

import me.kzheart.klib.scheduler.TaskHandle
import me.kzheart.klib.scheduler.Ticks
import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope
import me.kzheart.klib.ui.drop.DropZoneController
import org.bukkit.inventory.ItemStack
import java.util.Objects
import java.util.Optional
import java.util.concurrent.atomic.AtomicLong

/** 通过子作用域持有菜单刷新任务和临时物品。 */
class MenuSession private constructor(private val parent: Scope, private val scope: Scope,
                                      @Volatile private var model: MenuModel,
                                      private val returnTarget: ItemReturnTarget, private val overflowSink: OverflowSink?) : Disposable {
    private val dropZones = ArrayList<DropZoneController>()
    private var closing = false
    private var closeResult: MenuCloseResult? = null

    @Synchronized @JvmName("validateModel") internal fun validateModel(replacement: MenuModel) {
        ensureOpen()
        require(replacement.size() == model.size() && replacement.title() == model.title()) { "A refresh cannot change menu size or title" }
        for (zone in dropZones) for (slot in zone.slots()) require(!replacement.entry(slot).isPresent) { "Refresh overlaps a drop zone: $slot" }
    }
    @Synchronized @JvmName("updateModel") internal fun updateModel(replacement: MenuModel) { validateModel(replacement); model = replacement }
    fun model(): MenuModel = model
    fun scope(): Scope = scope
    @Synchronized fun addDropZone(controller: DropZoneController): DropZoneController {
        ensureOpen()
        check(overflowSink != null) { "drop zones require an OverflowSink so scope shutdown cannot lose items" }
        for (slot in controller.slots()) {
            require(slot < model.size()) { "drop-zone slot is outside menu: $slot" }
            require(!model.entry(slot).isPresent) { "drop-zone slot overlaps menu entry: $slot" }
            require(dropZones.none { slot in it.slots() }) { "drop-zone slot is already owned: $slot" }
        }
        dropZones.add(controller)
        return controller
    }
    @Synchronized fun dropZones(): List<DropZoneController> = ArrayList(dropZones)
    @Synchronized fun dropZoneAt(slot: Int): Optional<DropZoneController> = Optional.ofNullable(dropZones.firstOrNull { slot in it.slots() })
    @Synchronized fun refreshEvery(period: Ticks, refresh: Runnable): TaskHandle { ensureOpen(); return scope.every(period, refresh) }

    /** 回调在会话锁外执行，避免归还物品和作用域拆除与状态锁形成死锁。 */
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    fun close(reason: CloseReason): MenuCloseResult {
        val zones = synchronized(this) {
            while (closing) {
                closeResult?.let { return it }
                try { (this as java.lang.Object).wait() }
                catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("interrupted while closing menu", interrupted)
                }
            }
            closeResult?.let { return it }
            closing = true
            ArrayList(dropZones)
        }
        val owned = zones.flatMap { it.closeAndDrain() }
        var overflow = cloneItems(owned)
        var returnFailure: RuntimeException? = null
        try {
            if (owned.isNotEmpty()) overflow = Objects.requireNonNull(returnTarget.returnItems(cloneItems(owned)), "return target result")
        } catch (failure: RuntimeException) {
            if (overflowSink == null) returnFailure = failure else overflow = cloneItems(owned)
        }
        if (overflowSink != null && overflow.isNotEmpty()) {
            try { overflow = Objects.requireNonNull(overflowSink.returnItems(cloneItems(overflow)), "overflow sink result") }
            catch (_: RuntimeException) { /* 保留准确溢出快照，供调用方恢复。 */ }
        }
        val result = MenuCloseResult(reason, overflow)
        synchronized(this) {
            closeResult = result
            closing = false
            (this as java.lang.Object).notifyAll()
        }
        parent.remove(this)
        try { scope.close() }
        catch (closeFailure: RuntimeException) {
            if (returnFailure != null) returnFailure.addSuppressed(closeFailure) else throw closeFailure
        }
        returnFailure?.let { throw it }
        return result
    }
    @Synchronized fun isClosed(): Boolean = closeResult != null
    override fun dispose() { close(CloseReason.SCOPE_CLOSED) }
    private fun ensureOpen() { check(!closing && closeResult == null && !scope.isClosed) { "menu session is closed" } }
    private fun cloneItems(items: List<ItemStack>): List<ItemStack> = items.map { it.clone() }

    companion object {
        private val SESSION_IDS = AtomicLong()
        @JvmStatic fun open(parent: Scope, name: String, model: MenuModel, returnTarget: ItemReturnTarget): MenuSession =
            open(parent, name, model, returnTarget, null)
        @JvmStatic fun open(parent: Scope, name: String, model: MenuModel, returnTarget: ItemReturnTarget, overflowSink: OverflowSink?): MenuSession {
            val businessName = name.trim()
            require(businessName.isNotEmpty()) { "menu name must not be blank" }
            val menuScope = parent.scope("menu:$businessName#${java.lang.Long.toUnsignedString(SESSION_IDS.incrementAndGet())}") { }
            return parent.install(MenuSession(parent, menuScope, model, returnTarget, overflowSink))
        }
    }
}
