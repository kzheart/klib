package me.kzheart.klib.ui

import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.Objects
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer
import java.util.function.Function
import me.kzheart.klib.KLogger
import me.kzheart.klib.item.InventoryItems
import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope
import me.kzheart.klib.ui.drop.DropZoneController
import me.kzheart.klib.ui.drop.InventoryAction
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryAction as BukkitInventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/** 菜单适配入口：集中保证会话身份、槽位所有权、提交顺序与物品回收。 */
class MenuRenderer private constructor(private val owner: Scope, private val plugin: Plugin,
    private val logger: KLogger, private val errorHandler: MenuErrorHandler,
    private val inventoryFactory: MenuInventoryFactory) : Listener, Disposable {
    private val open = Collections.newSetFromMap(IdentityHashMap<MenuHolder, Boolean>())
    private val warnedUnknownSounds = ConcurrentHashMap.newKeySet<String>()
    private var disposed = false

    fun open(player: Player, name: String, model: MenuModel): MenuHolder = open(player, name, model, Consumer { })
    fun open(player: Player, name: String, model: MenuModel, configure: Consumer<in MenuSession>): MenuHolder {
        ensureMainThread("打开菜单"); ensureOpen()
        holder(player.openInventory.topInventory)?.close(CloseReason.REPLACED)
        val session = MenuSession.open(owner, name, model, { returnToInventory(player, it) }, { dropAtPlayer(player, it) })
        val menuHolder = MenuHolder(player, session, this)
        try {
            configure.accept(session)
            val inventory = createInventory(inventoryFactory, menuHolder, model)
            menuHolder.attach(inventory)
            synchronized(this) { ensureOpen(); open.add(menuHolder) }
            render(menuHolder)
            player.openInventory(inventory)
            return menuHolder
        } catch (failure: Throwable) {
            synchronized(this) { open.remove(menuHolder) }
            try { session.close(CloseReason.RENDER_FAILED) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            menuHolder.getInventory()?.viewers?.toList()?.forEach { viewer ->
                try { viewer.closeInventory() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            }
            throw failure
        }
    }
    fun <S> openReactive(player: Player, name: String, initialState: S,
                          view: Function<in ReactiveMenuSession<S>, MenuModel>): ReactiveMenuSession<S> {
        return openReactive(player, MenuPage.builder(name, view).build(), initialState, Runnable { player.closeInventory() })
    }
    fun <S> openReactive(player: Player, page: MenuPage<S>, initialState: S, rootBack: Runnable): ReactiveMenuSession<S> {
        ensureMainThread("打开状态菜单"); ensureOpen()
        return openPage(player, page, initialState, MenuNavigation(this, player, rootBack))
    }
    internal fun <S> openPage(player: Player, page: MenuPage<S>, initialState: S, navigation: MenuNavigation): ReactiveMenuSession<S> {
        val reactive = ReactiveMenuSession(player, initialState, page.view)
        reactive.initialize(page, navigation)
        navigation.activate(reactive)
        try { reactive.mount(this, reactive.buildModel()); return reactive }
        catch (failure: Throwable) { reactive.release(); throw failure }
    }
    @JvmName("update") internal fun update(holder: MenuHolder, model: MenuModel) {
        ensureMainThread("刷新菜单"); ensureOwned(holder)
        holder.session().validateModel(model)
        renderModel(holder, model)
        holder.session().updateModel(model)
    }
    fun render(holder: MenuHolder) {
        ensureMainThread("渲染菜单"); ensureOwned(holder)
        holder.session().validateModel(holder.model())
        renderModel(holder, holder.model())
    }
    private fun renderModel(holder: MenuHolder, model: MenuModel) {
        val desired = LinkedHashMap<Int, ItemStack?>()
        model.entries().forEach { (slot, entry) -> desired[slot] = entry.item() }
        collectDropZones(holder, desired)
        commitSlots(holder, desired, true)
    }
    private fun collectDropZones(holder: MenuHolder, desired: MutableMap<Int, ItemStack?>) {
        for (zone in holder.session().dropZones()) {
            val contents = zone.snapshot()
            zone.slots().forEach { desired[it] = contents[it] }
        }
    }
    private fun renderDropZones(holder: MenuHolder) {
        val desired = LinkedHashMap<Int, ItemStack?>()
        collectDropZones(holder, desired)
        commitSlots(holder, desired, false)
    }
    private fun commitSlots(holder: MenuHolder, desired: Map<Int, ItemStack?>, complete: Boolean) {
        val inventory = Objects.requireNonNull(holder.getInventory(), "holder inventory")!!
        val slots = LinkedHashSet<Int>()
        if (complete) slots.addAll(holder.renderedItems.keys)
        slots.addAll(desired.keys)
        try {
            for (slot in slots) if (holder.renderedItems[slot] != desired[slot]) inventory.setItem(slot, desired[slot]?.clone())
            if (complete) holder.renderedItems.clear()
            desired.forEach { (slot, item) -> holder.renderedItems[slot] = item?.clone() }
        } catch (failure: RuntimeException) { failRender(holder, failure); throw failure }
        catch (failure: Error) { failRender(holder, failure); throw failure }
    }
    @JvmName("failRender") internal fun failRender(holder: MenuHolder, failure: Throwable) {
        logger.error("菜单渲染失败: title=${holder.model().title()} player=${holder.playerId()}", failure)
        try { close(holder, CloseReason.RENDER_FAILED) } catch (cleanup: RuntimeException) { failure.addSuppressed(cleanup) }
        holder.getInventory()?.viewers?.toList()?.forEach { viewer ->
            try { viewer.closeInventory() } catch (cleanup: RuntimeException) { failure.addSuppressed(cleanup) }
        }
    }
    @JvmName("close") internal fun close(holder: MenuHolder, reason: CloseReason): MenuCloseResult {
        ensureOwned(holder)
        val result = try { holder.session().close(reason) }
            finally { synchronized(this) { open.remove(holder) }; holder.renderedItems.clear() }
        reportUnreturned(holder, result)
        return result
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onClick(event: InventoryClickEvent) {
        val holder = holder(event.view.topInventory) ?: return
        if (event.whoClicked !== holder.viewer || holder.session().isClosed()) { event.isCancelled = true; return }
        val topSize = event.view.topInventory.size
        val rawSlot = event.rawSlot
        val top = MenuEventPolicy.isTopSlot(rawSlot, topSize)
        val type = MenuEventPolicy.clickType(event.click)
        val zone = if (top) holder.session().dropZoneAt(rawSlot).orElse(null) else null
        val hasZones = holder.session().dropZones().isNotEmpty()
        if (MenuEventPolicy.cancelClick(holder.model(), event.click, rawSlot, topSize, zone != null, hasZones)) event.isCancelled = true
        if (type == MenuClickType.DOUBLE_CLICK || type == MenuClickType.NUMBER_KEY) return
        when {
            !top && type.shift() && hasZones -> insertFromBottom(holder, event)
            zone != null -> handleDropZoneClick(holder, zone, event)
            top -> holder.model().entry(rawSlot).orElse(null)?.let { entry ->
                event.isCancelled = true
                if (type != MenuClickType.LEFT && type != MenuClickType.RIGHT && type != MenuClickType.SHIFT_LEFT && type != MenuClickType.SHIFT_RIGHT) return
                val player = event.whoClicked as Player
                if (dispatchAction(logger, errorHandler, holder.model(), entry, MenuClick(player, rawSlot, type))) playClick(player, entry)
            }
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDrag(event: InventoryDragEvent) {
        val holder = holder(event.view.topInventory) ?: return
        if (event.whoClicked !== holder.viewer || holder.session().isClosed()) { event.isCancelled = true; return }
        val topSize = event.view.topInventory.size
        val touchesZone = event.rawSlots.any { it >= 0 && it < topSize && holder.session().dropZoneAt(it).isPresent }
        if (!touchesZone && !MenuEventPolicy.cancelDrag(holder.model(), event.rawSlots, topSize)) return
        event.isCancelled = true
        if (touchesZone && !holder.session().isClosed()) handleDropZoneDrag(holder, event, topSize)
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onClose(event: InventoryCloseEvent) {
        val holder = holder(event.view.topInventory) ?: return
        if (event.player === holder.viewer) close(holder, CloseReason.PLAYER)
    }
    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        synchronized(this) { open.toList() }.filter { it.viewer === event.player }.forEach { close(it, CloseReason.PLAYER) }
    }
    override fun dispose() {
        val snapshot = synchronized(this) { if (disposed) return; disposed = true; open.toList() }
        var failure: Throwable? = null
        fun record(caught: Throwable) {
            if (failure == null) failure = caught else if (failure !== caught) failure.addSuppressed(caught)
        }
        try {
            for (holder in snapshot) {
                try { reportUnreturned(holder, holder.session().close(CloseReason.PLUGIN_DISABLED)) }
                catch (caught: Throwable) { record(caught) }
                holder.getInventory()?.viewers?.toList()?.forEach {
                    try { it.closeInventory() } catch (caught: Throwable) { record(caught) }
                }
            }
        } finally {
            HandlerList.unregisterAll(this)
            synchronized(this) { open.clear() }
        }
        failure?.let { throw it }
    }

    private fun handleDropZoneClick(holder: MenuHolder, zone: DropZoneController, event: InventoryClickEvent) {
        val slot = event.rawSlot
        val action = event.action
        when (action) {
            BukkitInventoryAction.PLACE_ALL, BukkitInventoryAction.PLACE_SOME, BukkitInventoryAction.PLACE_ONE -> {
                val cursor = event.cursor
                if (!InventoryItems.isAir(cursor)) {
                    val requested = if (action == BukkitInventoryAction.PLACE_ONE) 1 else cursor!!.amount
                    val result = zone.handle(InventoryAction.place(slot, cursor, requested))
                    setCursor(event, subtract(cursor!!, result.accepted()))
                }
            }
            BukkitInventoryAction.PICKUP_ALL, BukkitInventoryAction.PICKUP_SOME,
            BukkitInventoryAction.PICKUP_HALF, BukkitInventoryAction.PICKUP_ONE -> takeToCursor(zone, slot, action, event)
            BukkitInventoryAction.SWAP_WITH_CURSOR -> swapWithCursor(zone, slot, event)
            BukkitInventoryAction.DROP_ALL_SLOT, BukkitInventoryAction.DROP_ONE_SLOT -> {
                val current = zone.snapshot()[slot]
                if (current != null) {
                    val amount = if (action == BukkitInventoryAction.DROP_ONE_SLOT) 1 else current.amount
                    zone.handle(InventoryAction.take(slot, amount)).removed().orElse(null)?.let { removed ->
                        val player = event.whoClicked as Player
                        try { player.world.dropItemNaturally(player.location, removed) }
                        catch (failure: RuntimeException) {
                            zone.handle(InventoryAction.place(slot, removed, removed.amount))
                            plugin.logger.severe("Could not drop a menu item for player ${player.uniqueId}: ${failure.message}")
                        }
                    }
                }
            }
            else -> Unit
        }
        renderDropZones(holder)
    }
    private fun takeToCursor(zone: DropZoneController, slot: Int, action: BukkitInventoryAction, event: InventoryClickEvent) {
        val current = zone.snapshot()[slot] ?: return
        val cursor = event.cursor
        val capacity = if (InventoryItems.isAir(cursor)) current.maxStackSize
            else if (cursor!!.isSimilar(current)) cursor.maxStackSize - cursor.amount else 0
        if (capacity <= 0) return
        val requested = when (action) {
            BukkitInventoryAction.PICKUP_ONE -> 1
            BukkitInventoryAction.PICKUP_HALF -> (current.amount + 1) / 2
            else -> current.amount
        }
        zone.handle(InventoryAction.take(slot, minOf(requested, capacity))).removed().orElse(null)?.let { setCursor(event, combine(cursor, it)) }
    }
    private fun swapWithCursor(zone: DropZoneController, slot: Int, event: InventoryClickEvent) {
        val cursor = event.cursor
        val current = zone.snapshot()[slot]
        if (InventoryItems.isAir(cursor) || current == null) return
        val removed = zone.handle(InventoryAction.take(slot, current.amount))
        val placed = zone.handle(InventoryAction.place(slot, cursor, cursor!!.amount))
        if (placed.accepted() == cursor.amount) { setCursor(event, removed.removed().orElse(null)); return }
        if (placed.accepted() > 0) zone.handle(InventoryAction.take(slot, placed.accepted()))
        zone.handle(InventoryAction.place(slot, current, current.amount))
    }
    private fun insertFromBottom(holder: MenuHolder, event: InventoryClickEvent) {
        val source = event.currentItem
        if (InventoryItems.isAir(source)) return
        var remaining = source!!.amount
        for (zone in holder.session().dropZones()) {
            if (remaining == 0) break
            remaining -= zone.handle(InventoryAction.shiftInsert(source.clone().apply { amount = remaining })).accepted()
        }
        event.currentItem = withAmount(source, remaining)
        renderDropZones(holder)
    }
    private fun handleDropZoneDrag(holder: MenuHolder, event: InventoryDragEvent, topSize: Int) {
        val source = event.oldCursor
        if (InventoryItems.isAir(source)) return
        var accepted = 0
        for (zone in holder.session().dropZones()) {
            val before = zone.snapshot()
            val requested = LinkedHashMap<Int, Int>()
            for ((slot, item) in event.newItems) {
                if (slot < 0 || slot >= topSize || slot !in zone.slots()) continue
                val addition = item.amount - (before[slot]?.amount ?: 0)
                if (addition > 0) requested[slot] = addition
            }
            if (requested.isNotEmpty()) accepted += zone.handle(InventoryAction.drag(source, requested)).accepted()
        }
        event.view.cursor = subtract(source, accepted)
        renderDropZones(holder)
    }
    private fun playClick(player: Player, entry: MenuEntry) {
        val soundName = entry.clickSound().orElse("UI_BUTTON_CLICK")
        try { player.playSound(player.location, Sound.valueOf(soundName.trim().uppercase(Locale.ROOT)), .6f, 1f) }
        catch (_: IllegalArgumentException) { if (warnedUnknownSounds.add(soundName)) plugin.logger.warning("Unknown menu click sound: $soundName") }
    }
    private fun reportUnreturned(holder: MenuHolder, result: MenuCloseResult) {
        if (result.unreturned().isNotEmpty()) plugin.logger.severe("Could not return or drop ${result.unreturned().sumOf { it.amount }} menu item(s) for player ${holder.playerId()}")
    }
    @Synchronized private fun ensureOpen() { check(!disposed && !owner.isClosed) { "menu renderer is closed" } }
    private fun ensureOwned(holder: MenuHolder) { require(holder.renderer === this) { "menu holder belongs to another renderer" } }

    companion object {
        @JvmStatic fun install(owner: Scope, plugin: Plugin): MenuRenderer = install(owner, plugin,
            MenuErrorHandler { player, _, _, _ -> player.sendMessage("§c菜单操作失败，请稍后重试") })
        @JvmStatic fun install(owner: Scope, plugin: Plugin, errorHandler: MenuErrorHandler): MenuRenderer =
            install(owner, plugin, MenuInventoryFactory.bukkit(), errorHandler)
        @JvmStatic fun install(owner: Scope, plugin: Plugin, inventoryFactory: MenuInventoryFactory, errorHandler: MenuErrorHandler): MenuRenderer {
            check(!owner.isClosed) { "cannot install menu renderer in a closed scope" }
            val logger = owner.findCapability(KLogger::class.java).orElseGet { KLogger(plugin.logger) }
            val renderer = MenuRenderer(owner, plugin, logger, errorHandler, inventoryFactory)
            plugin.server.pluginManager.registerEvents(renderer, plugin)
            try { return owner.install(renderer) } catch (failure: RuntimeException) { HandlerList.unregisterAll(renderer); throw failure }
        }
        @JvmStatic @JvmName("createInventory") internal fun createInventory(factory: MenuInventoryFactory, holder: InventoryHolder, model: MenuModel): Inventory {
            val inventory = Objects.requireNonNull(factory.create(holder, model.size(), model.title()), "inventoryFactory returned null")
            require(inventory.size == model.size() && inventory.holder === holder) { "inventoryFactory must preserve the requested holder and size" }
            return inventory
        }
        @JvmStatic @JvmName("dispatchAction") internal fun dispatchAction(logger: KLogger, errorHandler: MenuErrorHandler, model: MenuModel, entry: MenuEntry, click: MenuClick): Boolean {
            try { entry.action().accept(click); return true }
            catch (failure: Throwable) {
                logger.error("菜单操作失败: title=${model.title()} player=${click.player().uniqueId} slot=${click.slot()}", failure)
                try { errorHandler.onError(click.player(), model, click, failure) }
                catch (handlerFailure: Throwable) { logger.error("菜单错误处理器失败: title=${model.title()} slot=${click.slot()}", handlerFailure) }
                if (failure is Error) throw failure
                return false
            }
        }
        @JvmStatic @JvmName("ensureMainThread") internal fun ensureMainThread(action: String) {
            val server = Bukkit.getServer()
            check(server == null || server.isPrimaryThread) { "${action}必须在服务器主线程执行，请先用 scope.sync(...) 或 thenSync(...) 切回主线程" }
        }
        private fun holder(inventory: Inventory?): MenuHolder? = inventory?.holder as? MenuHolder
        private fun setCursor(event: InventoryClickEvent, item: ItemStack?) { event.view.cursor = item?.takeUnless { InventoryItems.isAir(it) } }
        private fun combine(first: ItemStack?, second: ItemStack): ItemStack =
            if (InventoryItems.isAir(first)) second.clone() else first!!.clone().apply { amount += second.amount }
        private fun subtract(source: ItemStack, amount: Int): ItemStack? = withAmount(source, source.amount - amount)
        private fun withAmount(source: ItemStack, amount: Int): ItemStack? = if (amount <= 0) null else source.clone().apply { this.amount = amount }
        private fun returnToInventory(player: Player, items: List<ItemStack>): List<ItemStack> =
            if (items.isEmpty()) emptyList() else player.inventory.addItem(*items.map { it.clone() }.toTypedArray()).values.map { it.clone() }
        private fun dropAtPlayer(player: Player, items: List<ItemStack>): List<ItemStack> {
            val location = player.location
            return items.mapNotNull { item -> try { player.world.dropItemNaturally(location, item.clone()); null } catch (_: RuntimeException) { item.clone() } }
        }
    }
}
