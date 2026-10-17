package me.kzheart.klib.ui;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

import me.kzheart.klib.scheduler.AsyncTask;
import me.kzheart.klib.scheduler.KScheduler;
import me.kzheart.klib.scheduler.SchedulerFactory;
import me.kzheart.klib.scheduler.TaskHandle;
import me.kzheart.klib.scheduler.Ticks;
import me.kzheart.klib.scope.Scope;
import me.kzheart.klib.scope.ScopeImpl;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/** Deterministic server-thread/tick and inventory-write fixture, not a Bukkit compatibility test. */
final class MenuTestEnvironment implements AutoCloseable {
    final Thread serverThread = Thread.currentThread();
    final ScopeImpl scope = new ScopeImpl("menu-test");
    final Map<UUID, Player> online = new HashMap<UUID, Player>();
    final Map<Player, Inventory> current = new IdentityHashMap<Player, Inventory>();
    final Map<Inventory, Contents> contents = new IdentityHashMap<Inventory, Contents>();
    final List<Work> queue = new ArrayList<Work>();
    final List<ItemStack> returned = new ArrayList<ItemStack>();
    final Field serverField;
    final Object previousServer;
    final MenuRenderer menus;
    final Player player;
    final Plugin plugin;
    int tick;
    int opens;
    boolean rejectScheduling;
    boolean wrapTopInventory;

    MenuTestEnvironment() throws Exception {
        ItemFactory itemFactory = proxy(ItemFactory.class, (p, m, args) -> {
            switch (m.getName()) {
                case "getItemMeta": return meta(new HashMap<String, Object>());
                case "asMetaFor": return args[0];
                case "isApplicable": return true;
                case "equals": return metadata(args[0]).equals(metadata(args[1]));
                default: return value(m.getReturnType());
            }
        });
        PluginManager manager = proxy(PluginManager.class, (p, m, args) -> value(m.getReturnType()));
        Server server = proxy(Server.class, (p, m, args) -> {
            if (m.getName().equals("isPrimaryThread")) return Thread.currentThread() == serverThread;
            if (m.getName().equals("getPlayer")) return online.get(args[0]);
            if (m.getName().equals("getItemFactory")) return itemFactory;
            if (m.getName().equals("getPluginManager")) return manager;
            return value(m.getReturnType());
        });
        plugin = proxy(Plugin.class, (p, m, args) -> {
            if (m.getName().equals("getServer")) return server;
            if (m.getName().equals("getLogger")) return Logger.getLogger("menu-test");
            if (m.getName().equals("isEnabled")) return true;
            return value(m.getReturnType());
        });
        serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        previousServer = serverField.get(null);
        serverField.set(null, server);
        scope.registerCapability(SchedulerFactory.class, owner -> new KScheduler() {
            @Override public TaskHandle after(Ticks delay, Runnable action) { return schedule(owner, delay, action, false); }
            @Override public TaskHandle every(Ticks delay, Runnable action) { return schedule(owner, delay, action, true); }
            @Override public <T> AsyncTask<T> async(Supplier<T> action) { throw new UnsupportedOperationException(); }
        });
        menus = MenuRenderer.install(scope, plugin,
                (holder, size, title) -> inventory(holder, size), (p, model, click, failure) -> { });
        player = player(UUID.randomUUID());
    }

    Player player(UUID id) {
        final Player[] reference = new Player[1];
        PlayerInventory bag = proxy(PlayerInventory.class, (p, m, args) -> {
            if (m.getName().equals("addItem")) {
                for (ItemStack item : (ItemStack[]) args[0]) returned.add(item.clone());
                return Collections.emptyMap();
            }
            return value(m.getReturnType());
        });
        Player created = proxy(Player.class, (p, m, args) -> {
            switch (m.getName()) {
                case "getUniqueId": return id;
                case "getName": return "tester";
                case "isOnline": return online.get(id) == p;
                case "getInventory": return bag;
                case "getOpenInventory": return view(reference[0], current.get(p), bag);
                case "openInventory":
                    Inventory before = current.get(p);
                    if (before != null && before.getHolder() instanceof MenuHolder) {
                        menus.onClose(new InventoryCloseEvent(view(reference[0], before, bag)));
                    }
                    current.put(reference[0], (Inventory) args[0]); opens++;
                    return view(reference[0], (Inventory) args[0], bag);
                case "closeInventory":
                    Inventory closing = current.get(p);
                    current.put(reference[0], inventory(null, 9));
                    if (closing != null && closing.getHolder() instanceof MenuHolder) {
                        menus.onClose(new InventoryCloseEvent(view(reference[0], closing, bag)));
                    }
                    return null;
                case "hashCode": return System.identityHashCode(p);
                case "equals": return p == args[0];
                default: return value(m.getReturnType());
            }
        });
        reference[0] = created;
        online.put(id, created);
        current.put(created, inventory(null, 9));
        return created;
    }

    Contents contents(MenuHolder holder) { return contents.get(holder.getInventory()); }

    Inventory inventory(InventoryHolder holder, int size) {
        Contents state = new Contents(size);
        Inventory created = proxy(Inventory.class, (p, m, args) -> {
            switch (m.getName()) {
                case "getHolder": return holder;
                case "getSize": return size;
                case "getItem": return cloneItem(state.slots[((Integer) args[0]).intValue()]);
                case "setItem":
                    if (Thread.currentThread() != serverThread) throw new AssertionError("off-thread inventory write");
                    int slot = ((Integer) args[0]).intValue();
                    if (slot == state.failSlot) throw new IllegalStateException("simulated setItem failure");
                    state.writes.add(Integer.valueOf(slot));
                    state.slots[slot] = cloneItem((ItemStack) args[1]); return null;
                case "clear": throw new AssertionError("diff must not clear the whole inventory");
                case "getViewers":
                    List<HumanEntity> viewers = new ArrayList<HumanEntity>();
                    for (Map.Entry<Player, Inventory> entry : current.entrySet()) {
                        if (entry.getValue() == p) viewers.add(entry.getKey());
                    }
                    return viewers;
                case "getType": return InventoryType.CHEST;
                case "hashCode": return System.identityHashCode(p);
                case "equals": return p == args[0];
                default: return value(m.getReturnType());
            }
        });
        contents.put(created, state);
        return created;
    }

    private InventoryView view(Player viewer, Inventory top, PlayerInventory bag) {
        return new InventoryView() {
            @Override public Inventory getTopInventory() {
                return wrapTopInventory ? wrap(top, top.getHolder()) : top;
            }
            @Override public Inventory getBottomInventory() { return bag; }
            @Override public HumanEntity getPlayer() { return viewer; }
            @Override public InventoryType getType() { return InventoryType.CHEST; }
        };
    }

    /** Different Bukkit wrapper objects can retain the same backing contents and owner. */
    Inventory wrap(Inventory source, InventoryHolder owner) {
        return proxy(Inventory.class, (p, method, args) -> {
            if (method.getName().equals("getHolder")) return owner;
            if (method.getName().equals("hashCode")) return System.identityHashCode(p);
            if (method.getName().equals("equals")) return p == args[0];
            return method.invoke(source, args);
        });
    }

    private synchronized TaskHandle schedule(Scope owner, Ticks delay, Runnable action, boolean repeat) {
        if (rejectScheduling) throw new java.util.concurrent.RejectedExecutionException("test scheduler full");
        Work work = new Work(owner, tick + Math.max(1, delay.value()), action, repeat);
        queue.add(work);
        return owner.install(work);
    }

    synchronized int queued() {
        int result = 0;
        for (Work work : queue) if (!work.isDone()) result++;
        return result;
    }

    void tick() {
        List<Work> due = new ArrayList<Work>();
        synchronized (this) {
            tick++;
            for (Work work : queue) if (!work.isDone() && work.due <= tick) due.add(work);
        }
        for (Work work : due) work.run();
    }

    Throwable worker(Runnable action) throws InterruptedException {
        final Throwable[] failure = new Throwable[1];
        Thread thread = new Thread(() -> { try { action.run(); } catch (Throwable caught) { failure[0] = caught; } });
        thread.start(); thread.join(); return failure[0];
    }

    @Override public void close() throws IllegalAccessException {
        try { scope.close(); } finally { serverField.set(null, previousServer); }
    }

    static ItemStack icon(Material material, int amount, String lore) {
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setLore(Collections.singletonList(lore));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack cloneItem(ItemStack item) { return item == null ? null : item.clone(); }

    static final class Contents {
        final ItemStack[] slots;
        final List<Integer> writes = new ArrayList<Integer>();
        int failSlot = -1;
        Contents(int size) { slots = new ItemStack[size]; }
    }

    private final class Work implements TaskHandle {
        final Scope owner;
        final long due;
        final Runnable action;
        final boolean repeat;
        volatile boolean cancelled;
        volatile boolean done;
        Work(Scope owner, long due, Runnable action, boolean repeat) {
            this.owner = owner; this.due = due; this.action = action; this.repeat = repeat;
        }
        void run() {
            if (isDone()) return;
            if (!repeat) { done = true; owner.remove(this); }
            action.run();
        }
        @Override public boolean cancel() {
            if (isDone()) return false;
            cancelled = true; owner.remove(this); return true;
        }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public boolean isDone() { return done || cancelled; }
    }

    private static final class Meta implements InvocationHandler {
        final Map<String, Object> data;
        Meta(Map<String, Object> data) { this.data = data; }
        @Override public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            switch (method.getName()) {
                case "clone": return meta(new HashMap<String, Object>(data));
                case "setLore": data.put("lore", new ArrayList<Object>((List<?>) args[0])); return null;
                case "getLore": return data.get("lore");
                case "hasLore": return data.containsKey("lore");
                case "setDisplayName": data.put("name", args[0]); return null;
                case "getDisplayName": return data.get("name");
                case "equals": return data.equals(metadata(args[0]));
                case "hashCode": return data.hashCode();
                default: return value(method.getReturnType());
            }
        }
    }
    private static ItemMeta meta(Map<String, Object> data) { return proxy(ItemMeta.class, new Meta(data)); }
    private static Map<String, Object> metadata(Object value) {
        return value == null ? Collections.<String, Object>emptyMap() : ((Meta) Proxy.getInvocationHandler(value)).data;
    }
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
    private static Object value(Class<?> type) {
        if (type == Boolean.TYPE) return false;
        if (type == Integer.TYPE) return 0;
        if (type == Long.TYPE) return 0L;
        if (type == Double.TYPE) return 0D;
        if (type == Float.TYPE) return 0F;
        if (type == Short.TYPE) return (short) 0;
        if (type == Byte.TYPE) return (byte) 0;
        return null;
    }
}
