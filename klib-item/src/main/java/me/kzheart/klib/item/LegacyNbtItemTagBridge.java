package me.kzheart.klib.item;

import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 基于内嵌 Item-NBT-API 的旧版物品 NBT 存储。 */
public final class LegacyNbtItemTagBridge implements ItemTagBridge {
    private static final String NBT_ITEM = "de.tr7zw.changeme.nbtapi.NBTItem";
    private static volatile Boolean serverSupported;
    private static volatile Constructor<?> nbtConstructor;
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<String, Method>();

    /** 服务端能力：内嵌的 Item-NBT-API 是否存在（静态缓存）。 */
    public boolean supports() {
        Boolean cached = serverSupported;
        if (cached == null) {
            try {
                Class.forName(NBT_ITEM);
                cached = Boolean.TRUE;
            } catch (ClassNotFoundException ignored) {
                cached = Boolean.FALSE;
            }
            serverSupported = cached;
        }
        return cached;
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
