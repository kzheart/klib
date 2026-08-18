package me.kzheart.klib.ui;

import me.kzheart.klib.KLogger;
import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.Scope;
import me.kzheart.klib.ui.drop.DropResult;
import me.kzheart.klib.ui.drop.DropZoneController;
import me.kzheart.klib.ui.drop.InventoryAction;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** 负责打开、渲染、派发并安全关闭已编译菜单的 Bukkit 桥接。 */
public final class MenuRenderer implements Listener, Disposable {
    private final Scope owner;
    private final Plugin plugin;
    private final KLogger logger;
    private final MenuErrorHandler errorHandler;
    private final Set<MenuHolder> open = Collections.newSetFromMap(
            new IdentityHashMap<MenuHolder, Boolean>());
    // 未知音效名只警告一次，避免每次点击刷日志。
    private final Set<String> warnedUnknownSounds = ConcurrentHashMap.newKeySet();
    private boolean disposed;

    private MenuRenderer(
            Scope owner,
            Plugin plugin,
            KLogger logger,
            MenuErrorHandler errorHandler
    ) {
        this.owner = owner;
        this.plugin = plugin;
        this.logger = logger;
        this.errorHandler = errorHandler;
    }

    /** 注册一个生命周期归属给定作用域的统一监听器。 */
    public static MenuRenderer install(Scope owner, Plugin plugin) {
        return install(owner, plugin, (player, model, click, failure) ->
                player.sendMessage("§c菜单操作失败，请稍后重试"));
    }

    /** 注册带可配置玩家动作错误边界的渲染器。 */
    public static MenuRenderer install(
            Scope owner,
            Plugin plugin,
            MenuErrorHandler errorHandler
    ) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(errorHandler, "errorHandler");
        if (owner.isClosed()) {
            throw new IllegalStateException("cannot install menu renderer in a closed scope");
        }
        KLogger logger = owner.findCapability(KLogger.class)
                .orElseGet(() -> new KLogger(plugin.getLogger()));
        MenuRenderer renderer = new MenuRenderer(owner, plugin, logger, errorHandler);
        plugin.getServer().getPluginManager().registerEvents(renderer, plugin);
        try {
            return owner.install(renderer);
        } catch (RuntimeException failure) {
            HandlerList.unregisterAll(renderer);
            throw failure;
        }
    }

    public MenuHolder open(Player player, String name, MenuModel model) {
        return open(player, name, model, session -> { });
    }

    /** 在物品栏首次渲染和打开前配置投放区。 */
    public MenuHolder open(
            Player player,
            String name,
            MenuModel model,
            Consumer<? super MenuSession> configure
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(configure, "configure");
        ensureMainThread("打开菜单");
        ensureOpen();

        MenuHolder previous = holder(player.getOpenInventory().getTopInventory());
        if (previous != null) {
            previous.close(CloseReason.REPLACED);
        }

        MenuSession session = MenuSession.open(
                owner,
                name,
                model,
                items -> returnToInventory(player, items),
                items -> dropAtPlayer(player, items));
        MenuHolder menuHolder = new MenuHolder(player.getUniqueId(), session, this);
        try {
            configure.accept(session);
            Inventory inventory = Bukkit.createInventory(menuHolder, model.size(), model.title());
            menuHolder.attach(inventory);
            synchronized (this) {
                ensureOpen();
                open.add(menuHolder);
            }
            render(menuHolder);
            player.openInventory(inventory);
            return menuHolder;
        } catch (RuntimeException failure) {
            synchronized (this) {
                open.remove(menuHolder);
            }
            try {
                session.close(CloseReason.REPLACED);
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /** 先应用不可变菜单条目，再应用权威投放区状态。 */
    public void render(MenuHolder holder) {
        Objects.requireNonNull(holder, "holder");
        ensureMainThread("渲染菜单");
        ensureOwned(holder);
        Inventory inventory = Objects.requireNonNull(holder.getInventory(), "holder inventory");
        inventory.clear();
        for (Map.Entry<Integer, MenuEntry> entry : holder.model().entries().entrySet()) {
            inventory.setItem(entry.getKey().intValue(), entry.getValue().item());
        }
        renderDropZones(holder);
    }

    MenuCloseResult close(MenuHolder holder, CloseReason reason) {
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(reason, "reason");
        ensureOwned(holder);
        MenuCloseResult result = holder.session().close(reason);
        synchronized (this) {
            open.remove(holder);
        }
        reportUnreturned(holder, result);
        return result;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        MenuHolder holder = holder(event.getView().getTopInventory());
        if (holder == null) {
            return;
        }
        if (!holder.playerId().equals(event.getWhoClicked().getUniqueId())
                || !(event.getWhoClicked() instanceof Player)) {
            event.setCancelled(true);
            return;
        }
        if (holder.session().isClosed()) {
            event.setCancelled(true);
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        int rawSlot = event.getRawSlot();
        boolean top = MenuEventPolicy.isTopSlot(rawSlot, topSize);
        MenuClickType type = MenuEventPolicy.clickType(event.getClick());
        Optional<DropZoneController> zone = top
                ? holder.session().dropZoneAt(rawSlot)
                : Optional.<DropZoneController>empty();
        boolean hasDropZones = !holder.session().dropZones().isEmpty();
        boolean shiftIntoZones = !top && type.shift() && hasDropZones;
        boolean cancelled = MenuEventPolicy.cancelClick(
                holder.model(), event.getClick(), rawSlot, topSize, zone.isPresent(), hasDropZones);
        if (cancelled) {
            event.setCancelled(true);
        }

        if (type == MenuClickType.DOUBLE_CLICK || type == MenuClickType.NUMBER_KEY) {
            return;
        }
        if (shiftIntoZones) {
            insertFromBottom(holder, event);
            return;
        }
        if (zone.isPresent()) {
            handleDropZoneClick(holder, zone.get(), event);
            return;
        }
        if (top) {
            Optional<MenuEntry> entry = holder.model().entry(rawSlot);
            if (entry.isPresent()) {
                // 即使禁用了菜单常规取消，已编译条目仍是框架持有的展示物品。
                event.setCancelled(true);
                Player player = (Player) event.getWhoClicked();
                MenuClick click = new MenuClick(player, rawSlot, type);
                if (dispatchAction(logger, errorHandler, holder.model(), entry.get(), click)) {
                    playClick(player, entry.get());
                }
            }
        }
    }

    static boolean dispatchAction(
            KLogger logger,
            MenuErrorHandler errorHandler,
            MenuModel model,
            MenuEntry entry,
            MenuClick click
    ) {
        Objects.requireNonNull(logger, "logger");
        Objects.requireNonNull(errorHandler, "errorHandler");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(click, "click");
        try {
            entry.action().accept(click);
            return true;
        } catch (Throwable failure) {
            logger.error("菜单操作失败: title=" + model.title()
                    + " player=" + click.player().getUniqueId()
                    + " slot=" + click.slot(), failure);
            try {
                errorHandler.onError(click.player(), model, click, failure);
            } catch (Throwable handlerFailure) {
                logger.error("菜单错误处理器失败: title=" + model.title()
                        + " slot=" + click.slot(), handlerFailure);
            }
            if (failure instanceof Error) {
                throw (Error) failure;
            }
            return false;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        MenuHolder holder = holder(event.getView().getTopInventory());
        if (holder == null) {
            return;
        }
        if (!holder.playerId().equals(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        boolean touchesZone = touchesDropZone(holder, event.getRawSlots(), topSize);
        if (!touchesZone && !MenuEventPolicy.cancelDrag(holder.model(), event.getRawSlots(), topSize)) {
            return;
        }
        event.setCancelled(true);
        if (!touchesZone || holder.session().isClosed()) {
            return;
        }
        handleDropZoneDrag(holder, event, topSize);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onClose(InventoryCloseEvent event) {
        MenuHolder holder = holder(event.getView().getTopInventory());
        if (holder != null && holder.playerId().equals(event.getPlayer().getUniqueId())) {
            close(holder, CloseReason.PLAYER);
        }
    }

    @Override
    public void dispose() {
        List<MenuHolder> snapshot;
        synchronized (this) {
            if (disposed) {
                return;
            }
            disposed = true;
            snapshot = new ArrayList<MenuHolder>(open);
        }
        HandlerList.unregisterAll(this);
        for (MenuHolder holder : snapshot) {
            MenuCloseResult result = holder.session().close(CloseReason.PLUGIN_DISABLED);
            reportUnreturned(holder, result);
            Inventory inventory = holder.getInventory();
            if (inventory != null) {
                for (org.bukkit.entity.HumanEntity viewer
                        : new ArrayList<org.bukkit.entity.HumanEntity>(inventory.getViewers())) {
                    viewer.closeInventory();
                }
            }
        }
        synchronized (this) {
            open.clear();
        }
    }

    private void handleDropZoneClick(
            MenuHolder holder,
            DropZoneController zone,
            InventoryClickEvent event
    ) {
        int slot = event.getRawSlot();
        org.bukkit.event.inventory.InventoryAction action = event.getAction();
        if (action == org.bukkit.event.inventory.InventoryAction.PLACE_ALL
                || action == org.bukkit.event.inventory.InventoryAction.PLACE_SOME
                || action == org.bukkit.event.inventory.InventoryAction.PLACE_ONE) {
            ItemStack cursor = event.getCursor();
            if (!empty(cursor)) {
                int requested = action == org.bukkit.event.inventory.InventoryAction.PLACE_ONE
                        ? 1 : cursor.getAmount();
                DropResult result = zone.handle(InventoryAction.place(slot, cursor, requested));
                setCursor(event, subtract(cursor, result.accepted()));
            }
        } else if (action == org.bukkit.event.inventory.InventoryAction.PICKUP_ALL
                || action == org.bukkit.event.inventory.InventoryAction.PICKUP_SOME
                || action == org.bukkit.event.inventory.InventoryAction.PICKUP_HALF
                || action == org.bukkit.event.inventory.InventoryAction.PICKUP_ONE) {
            takeToCursor(zone, slot, action, event);
        } else if (action == org.bukkit.event.inventory.InventoryAction.SWAP_WITH_CURSOR) {
            swapWithCursor(zone, slot, event);
        } else if (action == org.bukkit.event.inventory.InventoryAction.DROP_ALL_SLOT
                || action == org.bukkit.event.inventory.InventoryAction.DROP_ONE_SLOT) {
            ItemStack current = zone.snapshot().get(Integer.valueOf(slot));
            if (current != null) {
                int amount = action == org.bukkit.event.inventory.InventoryAction.DROP_ONE_SLOT
                        ? 1 : current.getAmount();
                DropResult result = zone.handle(InventoryAction.take(slot, amount));
                if (result.removed().isPresent()) {
                    Player player = (Player) event.getWhoClicked();
                    ItemStack removed = result.removed().get();
                    try {
                        player.getWorld().dropItemNaturally(player.getLocation(), removed);
                    } catch (RuntimeException failure) {
                        zone.handle(InventoryAction.place(slot, removed, removed.getAmount()));
                        plugin.getLogger().severe("Could not drop a menu item for player "
                                + player.getUniqueId() + ": " + failure.getMessage());
                    }
                }
            }
        }
        renderDropZones(holder);
    }

    private void takeToCursor(
            DropZoneController zone,
            int slot,
            org.bukkit.event.inventory.InventoryAction action,
            InventoryClickEvent event
    ) {
        ItemStack current = zone.snapshot().get(Integer.valueOf(slot));
        if (current == null) {
            return;
        }
        ItemStack cursor = event.getCursor();
        int capacity = empty(cursor)
                ? current.getMaxStackSize()
                : cursor.isSimilar(current) ? cursor.getMaxStackSize() - cursor.getAmount() : 0;
        if (capacity <= 0) {
            return;
        }
        int requested;
        if (action == org.bukkit.event.inventory.InventoryAction.PICKUP_ONE) {
            requested = 1;
        } else if (action == org.bukkit.event.inventory.InventoryAction.PICKUP_HALF) {
            requested = (current.getAmount() + 1) / 2;
        } else {
            requested = current.getAmount();
        }
        DropResult result = zone.handle(InventoryAction.take(slot, Math.min(requested, capacity)));
        if (result.removed().isPresent()) {
            setCursor(event, combine(cursor, result.removed().get()));
        }
    }

    private void swapWithCursor(
            DropZoneController zone,
            int slot,
            InventoryClickEvent event
    ) {
        ItemStack cursor = event.getCursor();
        ItemStack current = zone.snapshot().get(Integer.valueOf(slot));
        if (empty(cursor) || current == null) {
            return;
        }
        DropResult removed = zone.handle(InventoryAction.take(slot, current.getAmount()));
        DropResult placed = zone.handle(InventoryAction.place(slot, cursor, cursor.getAmount()));
        if (placed.accepted() == cursor.getAmount()) {
            setCursor(event, removed.removed().orElse(null));
            return;
        }
        if (placed.accepted() > 0) {
            zone.handle(InventoryAction.take(slot, placed.accepted()));
        }
        zone.handle(InventoryAction.place(slot, current, current.getAmount()));
    }

    private void insertFromBottom(MenuHolder holder, InventoryClickEvent event) {
        ItemStack source = event.getCurrentItem();
        if (empty(source)) {
            return;
        }
        int remaining = source.getAmount();
        for (DropZoneController zone : holder.session().dropZones()) {
            if (remaining == 0) {
                break;
            }
            ItemStack candidate = source.clone();
            candidate.setAmount(remaining);
            DropResult result = zone.handle(InventoryAction.shiftInsert(candidate));
            remaining -= result.accepted();
        }
        event.setCurrentItem(withAmount(source, remaining));
        renderDropZones(holder);
    }

    private void handleDropZoneDrag(MenuHolder holder, InventoryDragEvent event, int topSize) {
        ItemStack source = event.getOldCursor();
        if (empty(source)) {
            return;
        }
        int accepted = 0;
        for (DropZoneController zone : holder.session().dropZones()) {
            Map<Integer, ItemStack> before = zone.snapshot();
            Map<Integer, Integer> requested = new LinkedHashMap<Integer, Integer>();
            for (Map.Entry<Integer, ItemStack> entry : event.getNewItems().entrySet()) {
                int rawSlot = entry.getKey().intValue();
                if (rawSlot < 0 || rawSlot >= topSize || !zone.slots().contains(entry.getKey())) {
                    continue;
                }
                ItemStack previous = before.get(entry.getKey());
                int previousAmount = previous == null ? 0 : previous.getAmount();
                int addition = entry.getValue().getAmount() - previousAmount;
                if (addition > 0) {
                    requested.put(entry.getKey(), Integer.valueOf(addition));
                }
            }
            if (!requested.isEmpty()) {
                accepted += zone.handle(InventoryAction.drag(source, requested)).accepted();
            }
        }
        setCursor(event, subtract(source, accepted));
        renderDropZones(holder);
    }

    private static boolean touchesDropZone(MenuHolder holder, Set<Integer> rawSlots, int topSize) {
        for (Integer rawSlot : rawSlots) {
            if (rawSlot != null && rawSlot.intValue() >= 0 && rawSlot.intValue() < topSize
                    && holder.session().dropZoneAt(rawSlot.intValue()).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static void setCursor(InventoryClickEvent event, ItemStack item) {
        event.getView().setCursor(empty(item) ? null : item);
    }

    private static void setCursor(InventoryDragEvent event, ItemStack item) {
        event.getView().setCursor(empty(item) ? null : item);
    }

    private static ItemStack combine(ItemStack first, ItemStack second) {
        if (empty(first)) {
            return second.clone();
        }
        ItemStack result = first.clone();
        result.setAmount(first.getAmount() + second.getAmount());
        return result;
    }

    private static ItemStack subtract(ItemStack source, int amount) {
        return withAmount(source, source.getAmount() - amount);
    }

    private static ItemStack withAmount(ItemStack source, int amount) {
        if (amount <= 0) {
            return null;
        }
        ItemStack result = source.clone();
        result.setAmount(amount);
        return result;
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.getType() == Material.AIR || item.getAmount() <= 0;
    }

    private void renderDropZones(MenuHolder holder) {
        Inventory inventory = holder.getInventory();
        for (DropZoneController zone : holder.session().dropZones()) {
            Map<Integer, ItemStack> contents = zone.snapshot();
            for (Integer slot : zone.slots()) {
                inventory.setItem(slot.intValue(), contents.get(slot));
            }
        }
    }

    private static List<ItemStack> returnToInventory(Player player, List<ItemStack> items) {
        if (items.isEmpty()) {
            return Collections.emptyList();
        }
        ItemStack[] input = new ItemStack[items.size()];
        for (int index = 0; index < items.size(); index++) {
            input[index] = items.get(index).clone();
        }
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(input);
        List<ItemStack> result = new ArrayList<ItemStack>(overflow.size());
        for (ItemStack item : overflow.values()) {
            result.add(item.clone());
        }
        return result;
    }

    private static List<ItemStack> dropAtPlayer(Player player, List<ItemStack> items) {
        Location location = player.getLocation();
        List<ItemStack> unreturned = new ArrayList<ItemStack>();
        for (ItemStack item : items) {
            try {
                player.getWorld().dropItemNaturally(location, item.clone());
            } catch (RuntimeException failure) {
                unreturned.add(item.clone());
            }
        }
        return unreturned;
    }

    private void playClick(Player player, MenuEntry entry) {
        String soundName = entry.clickSound().orElse("UI_BUTTON_CLICK");
        try {
            Sound sound = Sound.valueOf(soundName.trim().toUpperCase(Locale.ROOT));
            player.playSound(player.getLocation(), sound, 0.6F, 1.0F);
        } catch (IllegalArgumentException failure) {
            if (warnedUnknownSounds.add(soundName)) {
                plugin.getLogger().warning("Unknown menu click sound: " + soundName);
            }
        }
    }

    private void reportUnreturned(MenuHolder holder, MenuCloseResult result) {
        if (!result.unreturned().isEmpty()) {
            int amount = 0;
            for (ItemStack item : result.unreturned()) {
                amount += item.getAmount();
            }
            plugin.getLogger().severe("Could not return or drop " + amount
                    + " menu item(s) for player " + holder.playerId());
        }
    }

    /** 主线程断言；无服务端环境（如单元测试）时跳过检查。 */
    private static void ensureMainThread(String action) {
        org.bukkit.Server server = Bukkit.getServer();
        if (server != null && !server.isPrimaryThread()) {
            throw new IllegalStateException(action + "必须在服务器主线程执行，"
                    + "请先用 scope.sync(...) 或 thenSync(...) 切回主线程");
        }
    }

    private synchronized void ensureOpen() {
        if (disposed || owner.isClosed()) {
            throw new IllegalStateException("menu renderer is closed");
        }
    }

    private void ensureOwned(MenuHolder holder) {
        if (holder.renderer != this) {
            throw new IllegalArgumentException("menu holder belongs to another renderer");
        }
    }

    private static MenuHolder holder(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof MenuHolder ? (MenuHolder) holder : null;
    }
}
