package me.kzheart.klib.item;

import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 访问 1.12.2 API 中尚不存在的 ItemMeta 方法；按实现类缓存查找结果。 */
final class ItemMetaReflection {
    private static final ConcurrentHashMap<String, Optional<Method>> METHODS =
            new ConcurrentHashMap<String, Optional<Method>>();

    private ItemMetaReflection() {
    }

    static boolean supports(ItemMeta meta, String name, Class<?>... parameters) {
        return find(meta.getClass(), name, parameters).isPresent();
    }

    /** 方法不存在时返回 {@code false}，不抛出。 */
    static boolean invokeIfPresent(ItemMeta meta, String name, Class<?>[] parameters, Object... arguments) {
        Optional<Method> method = find(meta.getClass(), name, parameters);
        if (!method.isPresent()) {
            return false;
        }
        invoke(method.get(), meta, arguments);
        return true;
    }

    static Object invokeOrNull(ItemMeta meta, String name) {
        Optional<Method> method = find(meta.getClass(), name);
        return method.isPresent() ? invoke(method.get(), meta) : null;
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

    private static Optional<Method> find(Class<?> type, String name, Class<?>... parameters) {
        StringBuilder key = new StringBuilder(type.getName()).append('#').append(name);
        for (Class<?> parameter : parameters) {
            key.append(',').append(parameter.getName());
        }
        return METHODS.computeIfAbsent(key.toString(), ignored -> {
            try {
                Method method = type.getMethod(name, parameters);
                method.setAccessible(true);
                return Optional.of(method);
            } catch (NoSuchMethodException | SecurityException missing) {
                return Optional.empty();
            }
        });
    }
}
