package me.kzheart.klib.lang;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;

/**
 * Paper-owned Adventure bridge. Native components are opaque Objects: shaded Adventure types
 * must never appear in method descriptors used to call the host API. All game operations are
 * main-thread only; serialization of a supplied immutable component is thread independent.
 */
public final class BukkitAdventure {
    private BukkitAdventure() { }
    private static Class<?> type(String suffix) {
        try {
            // Build the name at runtime so shading cannot rewrite the native host namespace.
            return Bukkit.class.getClassLoader().loadClass(String.join(".", "net", "kyori", "adventure", suffix));
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("The host does not provide Adventure: " + suffix, failure);
        }
    }
    public static boolean available() {
        try { type("text.minimessage.MiniMessage"); return true; }
        catch (IllegalStateException missing) { return false; }
    }
    private static Object invoke(Class<?> owner, Object receiver, String name, Class<?>[] signature, Object... args) {
        try {
            Method selected = null;
            try { selected = owner.getMethod(name, signature); }
            catch (NoSuchMethodException erasedGeneric) {
                for (Method method : owner.getMethods()) {
                    if (!method.getName().equals(name) || method.getParameterTypes().length != args.length) continue;
                    boolean compatible = true;
                    Class<?>[] parameters = method.getParameterTypes();
                    for (int i = 0; i < args.length; i++) {
                        if (args[i] != null && !parameters[i].isInstance(args[i])) compatible = false;
                    }
                    if (compatible) { selected = method; break; }
                }
            }
            if (selected == null) throw new NoSuchMethodException(owner.getName() + "." + name);
            return selected.invoke(receiver, args);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("Host Adventure call failed: " + name, failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Host Adventure method unavailable: " + name, failure);
        }
    }
    public static Object parse(String text) {
        Objects.requireNonNull(text, "text");
        if (text.indexOf('§') >= 0 || text.matches("(?s).*&[0-9a-fk-orA-FK-OR].*")) {
            Class<?> legacy = type("text.serializer.legacy.LegacyComponentSerializer");
            Object serializer = invoke(legacy, null, "legacySection", new Class<?>[0]);
            return invoke(legacy, serializer, "deserialize", new Class<?>[]{String.class}, ChatColor.translateAlternateColorCodes('&', text));
        }
        Class<?> mini = type("text.minimessage.MiniMessage");
        Object serializer = invoke(mini, null, "miniMessage", new Class<?>[0]);
        return invoke(mini, serializer, "deserialize", new Class<?>[]{String.class}, text);
    }
    public static String miniMessage(Object component) {
        Class<?> mini = type("text.minimessage.MiniMessage");
        Object serializer = invoke(mini, null, "miniMessage", new Class<?>[0]);
        return (String) invoke(mini, serializer, "serialize", new Class<?>[]{type("text.Component")}, Objects.requireNonNull(component, "component"));
    }
    public static String plain(Object component) {
        Class<?> plain = type("text.serializer.plain.PlainTextComponentSerializer");
        Object serializer = invoke(plain, null, "plainText", new Class<?>[0]);
        return (String) invoke(plain, serializer, "serialize", new Class<?>[]{type("text.Component")}, Objects.requireNonNull(component, "component"));
    }
    public static String legacy(String text) {
        Class<?> legacy = type("text.serializer.legacy.LegacyComponentSerializer");
        Object serializer = invoke(legacy, null, "legacySection", new Class<?>[0]);
        return (String) invoke(legacy, serializer, "serialize", new Class<?>[]{type("text.Component")}, parse(text));
    }
    public static void actionbar(Player player, String text) {
        mainThread();
        invoke(Player.class, player, "sendActionBar", new Class<?>[]{type("text.Component")}, parse(text));
    }
    public static Object displayName(ItemStack item) {
        mainThread();
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) return invoke(ItemMeta.class, meta, "displayName", new Class<?>[0]);
        return parse(item.getType().name());
    }
    public static List<Object> lore(ItemMeta meta) {
        mainThread();
        Object value = invoke(ItemMeta.class, meta, "lore", new Class<?>[0]);
        if (value == null) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<Object>((List<?>) value));
    }
    public static Inventory inventory(InventoryHolder holder, int size, String title) {
        mainThread();
        return (Inventory) invoke(Bukkit.class, null, "createInventory", new Class<?>[]{InventoryHolder.class, int.class, type("text.Component")}, holder, Integer.valueOf(size), parse(title));
    }
    public static void book(Player player, String title, String author, List<String> pages) {
        mainThread();
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) item.getItemMeta();
        meta.setTitle(title); meta.setAuthor(author);
        List<Object> components = new ArrayList<Object>();
        for (String page : pages) components.add(parse(page));
        invoke(BookMeta.class, meta, "pages", new Class<?>[]{List.class}, components);
        item.setItemMeta(meta);
        invoke(Player.class, player, "openBook", new Class<?>[]{ItemStack.class}, item);
    }
    private static void mainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Paper Adventure operation requires the main thread");
    }
}
