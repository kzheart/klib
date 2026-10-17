package me.kzheart.klib.item;

import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * 内置外部物品系统适配器。全部通过反射调用，业务插件无需在编译期依赖这些插件。
 *
 * <p>传入的类加载器应能看到目标插件的类，通常是 {@code plugin.getClass().getClassLoader()}。
 * 构造时解析全部所需类与方法，API 形状不符时立即抛出 {@link IllegalStateException}。
 */
public final class ExternalItemSources {
    private ExternalItemSources() {
    }

    /** ZaphkielPlus Java API，唯一前缀 {@code zap}；直接使用实际插件对象解析公共服务。 */
    public static ExternalItemSource zaphkiel(Plugin plugin) {
        return new ZaphkielSource(plugin);
    }

    /**
     * CraftEngine，前缀 {@code ce}，ID 必须为 {@code namespace:id}。
     *
     * <p>内容是否完成加载由调用方显式提供；空物品表也可能是合法配置，不能当作未就绪。
     * 不自动加入 {@link ExternalItems#detect()}，请在获得可靠加载状态后通过 {@code with} 注册。
     * 真实生成调用公开定义的 {@code buildBukkitItem}；它会执行构建处理器，不能用来模拟安全预览。
     */
    public static ExternalItemSource craftEngine(Plugin plugin, BooleanSupplier contentReady) {
        return new CraftEngineSource(plugin, contentReady);
    }

    private static final class CraftEngineSource implements ExternalItemSource {
        private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

        private final Plugin owner;
        private final BooleanSupplier contentReady;
        private final Method byId;
        private final Method identify;
        private final Method build;
        private final Method buildForPlayer;

        CraftEngineSource(Plugin plugin, BooleanSupplier contentReady) {
            this.owner = Objects.requireNonNull(plugin, "plugin");
            this.contentReady = Objects.requireNonNull(contentReady, "contentReady");
            Class<?> api = type(plugin.getClass().getClassLoader(),
                    "net.momirealms.craftengine.bukkit.api.CraftEngineItems");
            byId = method(api, "byId", String.class);
            identify = method(api, "getCustomItemId", ItemStack.class);
            if (!Modifier.isStatic(byId.getModifiers()) || !Modifier.isStatic(identify.getModifiers())) {
                throw new IllegalStateException("CraftEngineItems entry points must be static");
            }
            Class<?> definition = byId.getReturnType();
            build = method(definition, "buildBukkitItem");
            buildForPlayer = method(definition, "buildBukkitItem", Player.class);
            if (!ItemStack.class.isAssignableFrom(build.getReturnType())
                    || !ItemStack.class.isAssignableFrom(buildForPlayer.getReturnType())) {
                throw new IllegalStateException("CraftEngine buildBukkitItem must return ItemStack");
            }
        }

        @Override public String prefix() { return "ce"; }
        @Override public String plugin() { return "CraftEngine"; }
        @Override public ItemStack create(String id) { return create(id, null); }

        @Override public ItemStack create(String id, Player player) {
            requireId(id);
            if (!ready()) return null;
            Object definition = invoke(byId, null, id);
            if (definition == null) return null;
            return (ItemStack) (player == null
                    ? invoke(build, definition)
                    : invoke(buildForPlayer, definition, player));
        }

        @Override public ItemPreview preview(String id, Player player) {
            requireId(id);
            try {
                if (!ready()) {
                    return ItemPreview.unavailable(ItemPreview.Status.NOT_READY,
                            "CraftEngine content is not ready or the plugin is disabled");
                }
                if (invoke(byId, null, id) == null) {
                    return ItemPreview.unavailable(ItemPreview.Status.UNKNOWN_ID, id);
                }
                return ItemPreview.unavailable(ItemPreview.Status.UNSUPPORTED,
                        "CraftEngine has no safe preview API; buildBukkitItem runs item processors");
            } catch (RuntimeException | LinkageError failure) {
                return ItemPreview.failed(failure);
            }
        }

        @Override public String identify(ItemStack item) {
            if (InventoryItems.isAir(item)) return null;
            // Retain read-only identity even before content load or after disable. Do not silently
            // turn tagged external items into vanilla ingredients when a definition is unavailable.
            Object id = invoke(identify, null, item);
            return id == null ? null : id.toString();
        }

        private boolean ready() {
            return owner.isEnabled() && contentReady.getAsBoolean();
        }

        private static void requireId(String id) {
            if (id == null || !ID.matcher(id).matches()) {
                throw new IllegalArgumentException("CraftEngine id must be namespace:id, got '" + id + "'");
            }
        }
    }

    private static final class ZaphkielSource implements ExternalItemSource {
        private final Plugin owner;
        private final Method items;
        private final Method registry;
        private final Method find;
        private final Method generate;
        private final Method preview;
        private final Method identify;

        ZaphkielSource(Plugin plugin) {
            owner = Objects.requireNonNull(plugin, "plugin");
            items = method(plugin.getClass(), "items");
            Object service = requireValue(invoke(items, owner), "ZaphkielPlus item service");
            generate = method(service.getClass(), "generateItemStack", String.class, Player.class);
            preview = optionalMethod(service.getClass(), "preview", String.class, Player.class);
            identify = method(service.getClass(), "id", ItemStack.class);
            registry = method(service.getClass(), "registry");
            Object definitions = requireValue(invoke(registry, service), "ZaphkielPlus definition registry");
            find = method(definitions.getClass(), "find", String.class);
        }

        @Override public String prefix() { return "zap"; }
        @Override public String plugin() { return "ZaphkielPlus"; }
        @Override public ItemStack create(String id) { return create(id, (Player) null); }

        @Override public ItemStack create(String id, Player player) {
            if (!owner.isEnabled()) return null;
            Object service = requireValue(invoke(items, owner), "ZaphkielPlus item service");
            Object definitions = requireValue(invoke(registry, service), "ZaphkielPlus definition registry");
            Object found = invoke(find, definitions, id);
            if (!(found instanceof Optional<?>)) throw new IllegalStateException("ZaphkielPlus find must return Optional");
            if (!((Optional<?>) found).isPresent()) return null;
            return (ItemStack) invoke(generate, service, id, player);
        }

        @Override public ItemPreview preview(String id, Player player) {
            if (!owner.isEnabled()) {
                return ItemPreview.unavailable(ItemPreview.Status.NOT_READY, "ZaphkielPlus is disabled");
            }
            if (preview == null) {
                return ItemPreview.unavailable(ItemPreview.Status.UNSUPPORTED,
                        "ZaphkielPlus does not expose preview(String, Player)");
            }
            try {
                Object service = invoke(items, owner);
                if (service == null) {
                    return ItemPreview.unavailable(ItemPreview.Status.NOT_READY,
                            "ZaphkielPlus item service is not initialized");
                }
                Object definitions = invoke(registry, service);
                if (definitions == null) {
                    return ItemPreview.unavailable(ItemPreview.Status.NOT_READY,
                            "ZaphkielPlus definition registry is not initialized");
                }
                Object found = invoke(find, definitions, id);
                if (!(found instanceof Optional<?>)) throw new IllegalStateException("ZaphkielPlus find must return Optional");
                if (!((Optional<?>) found).isPresent()) {
                    return ItemPreview.unavailable(ItemPreview.Status.UNKNOWN_ID, id);
                }
                return ItemPreview.ready((ItemStack) invoke(preview, service, id, player));
            } catch (RuntimeException | LinkageError failure) {
                return ItemPreview.failed(failure);
            }
        }

        @Override public ItemStack createStack(String id, int amount, Player player) {
            if (amount != 1) throw new IllegalArgumentException("Zap instances must be generated per unit; use ExternalItems.createBatch");
            return create(id, player);
        }

        @Override public String identify(ItemStack item) {
            return (String) invoke(identify, requireValue(invoke(items, owner), "ZaphkielPlus item service"), item);
        }
    }

    /** MMOItems 6.x，ID 形如 {@code TYPE:ID}，前缀 {@code mi}。 */
    public static ExternalItemSource mmoItems(ClassLoader loader) {
        return new MmoItemsSource(loader);
    }

    /** NeigeItems，ID 为物品 ID，前缀 {@code ni}。兼容静态与 {@code INSTANCE} 两种 ItemManager 形状。 */
    public static ExternalItemSource neigeItems(ClassLoader loader) {
        return new NeigeItemsSource(loader);
    }

    /** ItemsAdder，ID 为 {@code namespace:id}，前缀 {@code ia}。 */
    public static ExternalItemSource itemsAdder(ClassLoader loader) {
        return new ItemsAdderSource(loader);
    }

    /** MythicMobs 物品，前缀 {@code mm}。5.x 支持识别已有物品；4.x 只能生成，识别总是返回 {@code null}。 */
    public static ExternalItemSource mythicMobs(ClassLoader loader) {
        return new MythicMobsSource(loader);
    }

    private static final class MmoItemsSource implements ExternalItemSource {
        private final Object plugin;
        private final Method getItem;
        private final Method getType;
        private final Method getId;
        private final Method typeId;

        MmoItemsSource(ClassLoader loader) {
            Class<?> main = type(loader, "net.Indyuce.mmoitems.MMOItems");
            plugin = requireValue(staticField(main, "plugin"), "MMOItems.plugin");
            getItem = method(main, "getItem", String.class, String.class);
            getType = method(main, "getType", ItemStack.class);
            getId = method(main, "getID", ItemStack.class);
            typeId = method(type(loader, "net.Indyuce.mmoitems.api.Type"), "getId");
        }

        @Override
        public String prefix() {
            return "mi";
        }

        @Override
        public String plugin() {
            return "MMOItems";
        }

        @Override
        public ItemStack create(String id) {
            int separator = id.indexOf(':');
            if (separator <= 0 || separator == id.length() - 1) {
                throw new IllegalArgumentException("MMOItems id must be TYPE:ID, got '" + id + "'");
            }
            return (ItemStack) invoke(getItem, plugin, id.substring(0, separator), id.substring(separator + 1));
        }

        @Override
        public String identify(ItemStack item) {
            Object type = invoke(getType, null, item);
            if (type == null) {
                return null;
            }
            Object id = invoke(getId, null, item);
            return id == null ? null : invoke(typeId, type) + ":" + id;
        }
    }

    private static final class NeigeItemsSource implements ExternalItemSource {
        private final Object manager;
        private final Method getItemStack;
        private final Method identify;
        private final Method infoId;

        NeigeItemsSource(ClassLoader loader) {
            Class<?> managerType = type(loader, "pers.neige.neigeitems.manager.ItemManager");
            getItemStack = method(managerType, "getItemStack", String.class);
            manager = Modifier.isStatic(getItemStack.getModifiers())
                    ? null
                    : requireValue(staticField(managerType, "INSTANCE"), "ItemManager.INSTANCE");
            Class<?> utils = type(loader, "pers.neige.neigeitems.utils.ItemUtils");
            Method byId = optionalMethod(utils, "getItemId", ItemStack.class);
            if (byId != null && Modifier.isStatic(byId.getModifiers())) {
                identify = byId;
                infoId = null;
            } else {
                identify = method(utils, "isNiItem", ItemStack.class);
                infoId = method(identify.getReturnType(), "getId");
            }
        }

        @Override
        public String prefix() {
            return "ni";
        }

        @Override
        public String plugin() {
            return "NeigeItems";
        }

        @Override
        public ItemStack create(String id) {
            return (ItemStack) invoke(getItemStack, manager, id);
        }

        @Override
        public String identify(ItemStack item) {
            Object result = invoke(identify, null, item);
            if (result == null || infoId == null) {
                return (String) result;
            }
            return (String) invoke(infoId, result);
        }
    }

    private static final class ItemsAdderSource implements ExternalItemSource {
        private final Method getInstance;
        private final Method byItemStack;
        private final Method getItemStack;
        private final Method namespacedId;

        ItemsAdderSource(ClassLoader loader) {
            Class<?> stack = type(loader, "dev.lone.itemsadder.api.CustomStack");
            getInstance = method(stack, "getInstance", String.class);
            byItemStack = method(stack, "byItemStack", ItemStack.class);
            getItemStack = method(stack, "getItemStack");
            namespacedId = method(stack, "getNamespacedID");
        }

        @Override
        public String prefix() {
            return "ia";
        }

        @Override
        public String plugin() {
            return "ItemsAdder";
        }

        @Override
        public ItemStack create(String id) {
            Object stack = invoke(getInstance, null, id);
            return stack == null ? null : (ItemStack) invoke(getItemStack, stack);
        }

        @Override
        public String identify(ItemStack item) {
            Object stack = invoke(byItemStack, null, item);
            return stack == null ? null : (String) invoke(namespacedId, stack);
        }
    }

    private static final class MythicMobsSource implements ExternalItemSource {
        private final Method instance;
        private final Method itemManager;

        MythicMobsSource(ClassLoader loader) {
            Class<?> main = optionalType(loader, "io.lumine.mythic.bukkit.MythicBukkit");
            if (main == null) {
                main = type(loader, "io.lumine.xikage.mythicmobs.MythicMobs");
            }
            instance = method(main, "inst");
            itemManager = method(main, "getItemManager");
            method(itemManager.getReturnType(), "getItemStack", String.class);
        }

        @Override
        public String prefix() {
            return "mm";
        }

        @Override
        public String plugin() {
            return "MythicMobs";
        }

        @Override
        public ItemStack create(String id) {
            Object manager = manager();
            return (ItemStack) invoke(method(manager.getClass(), "getItemStack", String.class), manager, id);
        }

        @Override
        public String identify(ItemStack item) {
            Object manager = manager();
            Method type = optionalMethod(manager.getClass(), "getMythicTypeFromItem", ItemStack.class);
            return type == null ? null : (String) invoke(type, manager, item);
        }

        private Object manager() {
            return requireValue(invoke(itemManager, invoke(instance, null)), "MythicMobs item manager");
        }
    }

    private static Class<?> type(ClassLoader loader, String name) {
        Class<?> type = optionalType(loader, name);
        if (type == null) {
            throw new IllegalStateException("Class not found: " + name);
        }
        return type;
    }

    private static Class<?> optionalType(ClassLoader loader, String name) {
        try {
            return Class.forName(name, true, Objects.requireNonNull(loader, "loader"));
        } catch (ClassNotFoundException missing) {
            return null;
        }
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) {
        Method method = optionalMethod(type, name, parameters);
        if (method == null) {
            throw new IllegalStateException("Method not found: " + type.getName() + "#" + name);
        }
        return method;
    }

    private static Method optionalMethod(Class<?> type, String name, Class<?>... parameters) {
        try {
            Method method = type.getMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException missing) {
            return null;
        }
    }

    private static Object staticField(Class<?> type, String name) {
        try {
            Field field = type.getField(name);
            return field.get(null);
        } catch (NoSuchFieldException | IllegalAccessException failure) {
            throw new IllegalStateException("Field not found: " + type.getName() + "#" + name, failure);
        }
    }

    private static Object requireValue(Object value, String description) {
        if (value == null) {
            throw new IllegalStateException(description + " is not initialized");
        }
        return value;
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot access " + method, failure);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(method + " failed", cause);
        }
    }
}
