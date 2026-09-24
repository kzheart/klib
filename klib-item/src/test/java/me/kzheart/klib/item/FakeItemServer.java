package me.kzheart.klib.item;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/** 以动态代理模拟 ItemFactory 与 ItemMeta，让物品元数据逻辑可以脱离服务端测试。 */
final class FakeItemServer {
    /** 模拟 1.14+/1.20.5+ 才有的 ItemMeta 方法。 */
    public interface ModernMeta {
        boolean hasCustomModelData();

        Integer getCustomModelData();

        void setCustomModelData(Integer value);

        Boolean getEnchantmentGlintOverride();

        void setEnchantmentGlintOverride(Boolean value);
    }

    private static volatile boolean modern;

    private FakeItemServer() {
    }

    static synchronized void install(boolean modernMeta) {
        modern = modernMeta;
        if (Bukkit.getServer() == null) {
            Bukkit.setServer((Server) Proxy.newProxyInstance(FakeItemServer.class.getClassLoader(),
                    new Class<?>[]{Server.class}, FakeItemServer::server));
        }
    }

    private static Object server(Object proxy, Method method, Object[] arguments) {
        switch (method.getName()) {
            case "getItemFactory":
                return FACTORY;
            case "getLogger":
                return Logger.getLogger("FakeItemServer");
            case "getName":
            case "getVersion":
            case "getBukkitVersion":
                return "fake";
            case "getOfflinePlayer":
                UUID id = (UUID) arguments[0];
                return Proxy.newProxyInstance(FakeItemServer.class.getClassLoader(),
                        new Class<?>[]{OfflinePlayer.class},
                        (target, m, a) -> "getUniqueId".equals(m.getName()) ? id : defaultValue(m));
            default:
                return defaultValue(method);
        }
    }

    private static final ItemFactory FACTORY = (ItemFactory) Proxy.newProxyInstance(
            FakeItemServer.class.getClassLoader(), new Class<?>[]{ItemFactory.class},
            (proxy, method, arguments) -> {
                switch (method.getName()) {
                    case "getItemMeta":
                        String material = ((Material) arguments[0]).name();
                        return newMeta(new HashMap<String, Object>(),
                                material.equals("SKULL_ITEM") || material.equals("PLAYER_HEAD"));
                    case "isApplicable":
                        return Boolean.TRUE;
                    case "asMetaFor":
                        return arguments[0];
                    case "equals":
                        return normalized(arguments[0]).equals(normalized(arguments[1]));
                    default:
                        return defaultValue(method);
                }
            });

    private static Map<String, Object> state(Object meta) {
        return meta == null ? Collections.<String, Object>emptyMap() : ((MetaHandler) Proxy.getInvocationHandler(meta)).state;
    }

    private static Map<String, Object> normalized(Object meta) {
        Map<String, Object> result = new HashMap<String, Object>();
        for (Map.Entry<String, Object> entry : state(meta).entrySet()) {
            Object value = entry.getValue();
            boolean empty = value instanceof Map<?, ?> && ((Map<?, ?>) value).isEmpty()
                    || value instanceof java.util.Collection<?> && ((java.util.Collection<?>) value).isEmpty();
            if (!empty) {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    private static ItemMeta newMeta(Map<String, Object> state, boolean skull) {
        Class<?> base = skull ? SkullMeta.class : ItemMeta.class;
        Class<?>[] types = modern
                ? new Class<?>[]{base, ModernMeta.class}
                : new Class<?>[]{base};
        return (ItemMeta) Proxy.newProxyInstance(FakeItemServer.class.getClassLoader(), types,
                new MetaHandler(state, skull));
    }

    private static Object defaultValue(Method method) {
        Class<?> type = method.getReturnType();
        if (type == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (type == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        return null;
    }

    private static final class MetaHandler implements InvocationHandler {
        private final Map<String, Object> state;
        private final boolean skull;

        private MetaHandler(Map<String, Object> state, boolean skull) {
            this.state = state;
            this.skull = skull;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            String name = method.getName();
            Map<Enchantment, Integer> enchants = (Map<Enchantment, Integer>) state.computeIfAbsent(
                    "enchants", key -> new LinkedHashMap<Enchantment, Integer>());
            Set<ItemFlag> flags = (Set<ItemFlag>) state.computeIfAbsent(
                    "flags", key -> EnumSet.noneOf(ItemFlag.class));
            switch (name) {
                case "clone":
                    Map<String, Object> copy = new HashMap<String, Object>(state);
                    copy.put("enchants", new LinkedHashMap<Enchantment, Integer>(enchants));
                    copy.put("flags", flags.isEmpty() ? EnumSet.noneOf(ItemFlag.class) : EnumSet.copyOf(flags));
                    return newMeta(copy, skull);
                case "equals":
                    return arguments[0] != null && Proxy.isProxyClass(arguments[0].getClass())
                            && normalized(proxy).equals(normalized(arguments[0]));
                case "hashCode":
                    return state.hashCode();
                case "toString":
                    return "FakeMeta" + state;
                case "setDisplayName":
                    return put("name", arguments[0]);
                case "getDisplayName":
                    return state.get("name");
                case "hasDisplayName":
                    return state.get("name") != null;
                case "setLore":
                    return put("lore", arguments[0] == null ? null : new ArrayList<String>((List<String>) arguments[0]));
                case "getLore":
                    return state.get("lore") == null ? null : new ArrayList<String>((List<String>) state.get("lore"));
                case "hasLore":
                    return state.get("lore") != null;
                case "setUnbreakable":
                    return put("unbreakable", arguments[0]);
                case "isUnbreakable":
                    return Boolean.TRUE.equals(state.get("unbreakable"));
                case "addEnchant":
                    enchants.put((Enchantment) arguments[0], (Integer) arguments[1]);
                    return Boolean.TRUE;
                case "removeEnchant":
                    return enchants.remove(arguments[0]) != null;
                case "getEnchantLevel":
                    Integer level = enchants.get(arguments[0]);
                    return level == null ? 0 : level;
                case "hasEnchant":
                    return enchants.containsKey(arguments[0]);
                case "hasEnchants":
                    return !enchants.isEmpty();
                case "getEnchants":
                    return new LinkedHashMap<Enchantment, Integer>(enchants);
                case "addItemFlags":
                    Collections.addAll(flags, (ItemFlag[]) arguments[0]);
                    return null;
                case "removeItemFlags":
                    for (ItemFlag flag : (ItemFlag[]) arguments[0]) {
                        flags.remove(flag);
                    }
                    return null;
                case "hasItemFlag":
                    return flags.contains(arguments[0]);
                case "getItemFlags":
                    return EnumSet.copyOf(flags.isEmpty() ? EnumSet.noneOf(ItemFlag.class) : flags);
                case "setOwningPlayer":
                    put("owner", ((OfflinePlayer) arguments[0]).getUniqueId());
                    return Boolean.TRUE;
                case "hasOwner":
                    return state.get("owner") != null;
                case "setCustomModelData":
                    return put("cmd", arguments[0]);
                case "getCustomModelData":
                    return state.get("cmd");
                case "hasCustomModelData":
                    return state.get("cmd") != null;
                case "setEnchantmentGlintOverride":
                    return put("glint", arguments[0]);
                case "getEnchantmentGlintOverride":
                    return state.get("glint");
                default:
                    return defaultValue(method);
            }
        }

        private Object put(String key, Object value) {
            if (value == null) {
                state.remove(key);
            } else {
                state.put(key, value);
            }
            return null;
        }
    }

    static Object stateOf(ItemMeta meta, String key) {
        return state(meta).get(key);
    }

    static Material material(String name) {
        return Material.getMaterial(name);
    }
}
