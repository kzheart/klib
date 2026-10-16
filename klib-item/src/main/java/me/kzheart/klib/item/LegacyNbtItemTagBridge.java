package me.kzheart.klib.item;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 基于内嵌 Item-NBT-API 的旧版物品 NBT 存储。 */
public final class LegacyNbtItemTagBridge implements ItemTagBridge {
    private static final String NBT_ITEM = "de.tr7zw.changeme.nbtapi.NBTItem";
    private static final String MINECRAFT_VERSION = "de.tr7zw.changeme.nbtapi.utils.MinecraftVersion";
    private static volatile Boolean serverSupported;
    private static volatile Constructor<?> nbtConstructor;
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<String, Method>();

    /** 服务端能力：NBT API 识别当前平台，且提供 direct-apply 物品接口。 */
    public boolean supports() {
        // A pre-startup probe must not permanently cache an unavailable server.
        if (Bukkit.getServer() == null) return false;
        Boolean cached = serverSupported;
        if (cached == null) {
            synchronized (LegacyNbtItemTagBridge.class) {
                cached = serverSupported;
                if (cached == null) {
                    try {
                        ClassLoader loader = LegacyNbtItemTagBridge.class.getClassLoader();
                        Class<?> itemType = Class.forName(NBT_ITEM, false, loader);
                        Class<?> versionType = Class.forName(MINECRAFT_VERSION, true, loader);
                        cached = supportsPlatform(itemType, versionType);
                    } catch (ClassNotFoundException | LinkageError unavailable) {
                        cached = Boolean.FALSE;
                    }
                    serverSupported = cached;
                }
            }
        }
        return cached;
    }

    /** Separate the capability probe from item operations; data failures must still propagate. */
    static boolean supportsPlatform(Class<?> itemType, Class<?> versionType) {
        try {
            itemType.getConstructor(ItemStack.class, boolean.class);
            Object version = versionType.getMethod("getVersion").invoke(null);
            // Class presence alone is insufficient: unknown/newer server revisions cannot use NBTItem.
            return version instanceof Enum<?> && versionType.isInstance(version)
                    && !"UNKNOWN".equals(((Enum<?>) version).name());
        } catch (NoSuchMethodException | LinkageError unavailable) {
            return false;
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot inspect legacy NBT platform support", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof LinkageError) return false;
            throw new IllegalStateException("Cannot determine legacy NBT platform support", cause);
        }
    }

    @Override
    public <T> T get(ItemStack item, TagKey<T> key) {
        if (!has(item, key)) {
            return null;
        }
        Object nbt = wrap(item);
        Object stored = invoke(nbt, key.valueType().legacyGetter(),
                new Class<?>[]{String.class}, key.value());
        return key.fromStorage(stored);
    }

    @Override
    public <T> void set(ItemStack item, TagKey<T> key, T value) {
        Object stored = key.toStorage(value);
        Object nbt = wrap(item);
        invoke(nbt, key.valueType().legacySetter(),
                new Class<?>[]{String.class, stored.getClass()}, key.value(), stored);
    }

    @Override
    public boolean has(ItemStack item, TagKey<?> key) {
        Object result = invoke(wrap(item), "hasTag", new Class<?>[]{String.class}, key.value());
        return Boolean.TRUE.equals(result);
    }

    @Override
    public void remove(ItemStack item, TagKey<?> key) {
        invoke(wrap(item), "removeKey", new Class<?>[]{String.class}, key.value());
    }

    private static Object wrap(ItemStack item) {
        try {
            Constructor<?> direct = nbtConstructor;
            if (direct == null) {
                Class<?> type = Class.forName(NBT_ITEM);
                direct = type.getConstructor(ItemStack.class, boolean.class);
                nbtConstructor = direct;
            }
            return direct.newInstance(item, true);
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException
                | IllegalAccessException exception) {
            throw new IllegalStateException(
                    "Legacy NBT needs the shaded Item-NBT-API with direct-apply NBTItem support", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Cannot open legacy item NBT", exception.getCause());
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Method method = method(target.getClass(), name, parameterTypes);
            return method.invoke(target, arguments);
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new IllegalStateException("Unsupported Item-NBT-API method: " + name, exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("Legacy NBT operation failed: " + name, exception.getCause());
        }
    }

    /** 按名称和签名缓存方法句柄；NBTItem 只有一个类。 */
    private static Method method(Class<?> target, String name, Class<?>[] parameterTypes)
            throws NoSuchMethodException {
        StringBuilder cacheKey = new StringBuilder(name);
        for (Class<?> parameterType : parameterTypes) {
            cacheKey.append('|').append(parameterType.getName());
        }
        String signature = cacheKey.toString();
        Method cached = METHODS.get(signature);
        if (cached == null) {
            cached = target.getMethod(name, parameterTypes);
            METHODS.putIfAbsent(signature, cached);
        }
        return cached;
    }
}
