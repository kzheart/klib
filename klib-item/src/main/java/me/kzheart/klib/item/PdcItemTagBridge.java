package me.kzheart.klib.item;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 基于反射的 PDC 存储，使模块在 Bukkit 1.12 上仍可加载。 */
public final class PdcItemTagBridge implements ItemTagBridge {
    private static final String PERSISTENT_TYPE = "org.bukkit.persistence.PersistentDataType";
    private static volatile Boolean serverSupported;
    private static volatile Pdc pdcCache;
    private static final Map<String, Object> NAMESPACED_KEYS = new ConcurrentHashMap<String, Object>();
    private static final Map<String, Object> DATA_TYPES = new ConcurrentHashMap<String, Object>();

    /** 服务端能力：PDC API 是否存在（静态缓存）。 */
    public static boolean isServerSupported() {
        Boolean cached = serverSupported;
        if (cached == null) {
            try {
                Class.forName(PERSISTENT_TYPE);
                ItemMeta.class.getMethod("getPersistentDataContainer");
                cached = Boolean.TRUE;
            } catch (ClassNotFoundException | NoSuchMethodException ignored) {
                cached = Boolean.FALSE;
            }
            serverSupported = cached;
        }
        return cached;
    }

    /** 物品能力：服务端支持 PDC，且该物品带有元数据。 */
    public boolean supports(ItemStack item) {
        return isServerSupported() && item.getItemMeta() != null;
    }

    @Override
    public <T> T get(ItemStack item, TagKey<T> key) {
        Object value = invokeContainer(item, key, Operation.GET, null);
        return key.fromStorage(value);
    }

    @Override
    public <T> void set(ItemStack item, TagKey<T> key, T value) {
        invokeContainer(item, key, Operation.SET, key.toStorage(value));
    }

    @Override
    public boolean has(ItemStack item, TagKey<?> key) {
        return Boolean.TRUE.equals(invokeContainer(item, key, Operation.HAS, null));
    }

    @Override
    public void remove(ItemStack item, TagKey<?> key) {
        invokeContainer(item, key, Operation.REMOVE, null);
    }

    private Object invokeContainer(ItemStack item, TagKey<?> key, Operation operation, Object value) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            // 没有元数据的物品（如 AIR）无法携带标签：读取结果为空，删除不执行操作，
            // 写入则失败并给出原因。
            if (operation == Operation.SET) {
                throw new IllegalArgumentException("Cannot write tag " + key.value()
                        + ": item " + item.getType() + " has no metadata to store it on");
            }
            return null;
        }
        try {
            Pdc pdc = pdc();
            Object container = pdc.getContainer.invoke(meta);
            Object namespacedKey = namespacedKey(pdc, key.value());
            Object dataType = persistentDataType(pdc, key.valueType().pdcField());
            Object result;
            if (operation == Operation.GET) {
                result = pdc.get.invoke(container, namespacedKey, dataType);
            } else if (operation == Operation.HAS) {
                result = pdc.has.invoke(container, namespacedKey, dataType);
            } else if (operation == Operation.REMOVE) {
                pdc.remove.invoke(container, namespacedKey);
                result = null;
            } else {
                pdc.set.invoke(container, namespacedKey, dataType, value);
                result = null;
            }
            if (operation == Operation.SET || operation == Operation.REMOVE) {
                item.setItemMeta(meta);
            }
            return result;
        } catch (IllegalAccessException exception) {
            throw unavailable(exception);
        } catch (InvocationTargetException exception) {
            throw bridgeFailure(exception.getCause());
        }
    }

    /** 延迟解析并缓存的 PDC API 反射句柄。 */
    private static Pdc pdc() {
        Pdc cached = pdcCache;
        if (cached == null) {
            try {
                cached = new Pdc();
            } catch (ClassNotFoundException | NoSuchMethodException exception) {
                throw unavailable(exception);
            }
            pdcCache = cached;
        }
        return cached;
    }

    private static Object namespacedKey(Pdc pdc, String value) {
        Object cached = NAMESPACED_KEYS.get(value);
        if (cached == null) {
            int separator = value.indexOf(':');
            try {
                cached = pdc.keyConstructor.newInstance(
                        value.substring(0, separator), value.substring(separator + 1));
            } catch (InstantiationException | IllegalAccessException exception) {
                throw unavailable(exception);
            } catch (InvocationTargetException exception) {
                throw bridgeFailure(exception.getCause());
            }
            NAMESPACED_KEYS.putIfAbsent(value, cached);
        }
        return cached;
    }

    private static Object persistentDataType(Pdc pdc, String fieldName) {
        Object cached = DATA_TYPES.get(fieldName);
        if (cached == null) {
            try {
                Field field = pdc.typeClass.getField(fieldName);
                cached = field.get(null);
            } catch (NoSuchFieldException | IllegalAccessException exception) {
                throw unavailable(exception);
            }
            DATA_TYPES.putIfAbsent(fieldName, cached);
        }
        return cached;
    }

    private static IllegalStateException unavailable(Exception exception) {
        return new IllegalStateException("PersistentDataContainer is not available", exception);
    }

    private static IllegalStateException bridgeFailure(Throwable cause) {
        return new IllegalStateException("PersistentDataContainer operation failed", cause);
    }

    private static final class Pdc {
        private final Class<?> typeClass;
        private final Constructor<?> keyConstructor;
        private final Method getContainer;
        private final Method get;
        private final Method has;
        private final Method set;
        private final Method remove;

        private Pdc() throws ClassNotFoundException, NoSuchMethodException {
            Class<?> keyClass = Class.forName("org.bukkit.NamespacedKey");
            typeClass = Class.forName(PERSISTENT_TYPE);
            Class<?> containerClass = Class.forName("org.bukkit.persistence.PersistentDataContainer");
            keyConstructor = keyClass.getConstructor(String.class, String.class);
            getContainer = ItemMeta.class.getMethod("getPersistentDataContainer");
            get = containerClass.getMethod("get", keyClass, typeClass);
            has = containerClass.getMethod("has", keyClass, typeClass);
            set = containerClass.getMethod("set", keyClass, typeClass, Object.class);
            remove = containerClass.getMethod("remove", keyClass);
        }
    }

    private enum Operation {
        GET,
        SET,
        HAS,
        REMOVE
    }
}
