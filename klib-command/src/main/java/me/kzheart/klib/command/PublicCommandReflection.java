package me.kzheart.klib.command;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** 只调用公开 API；避免把现代 Paper 的 Java 21 类型写进 Java 8 产物签名。 */
final class PublicCommandReflection {
    private PublicCommandReflection() { }

    static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, false, PublicCommandReflection.class.getClassLoader());
    }

    static Object invoke(Object target, String name, Object... args) {
        Method method = find(target.getClass(), name, false, args);
        if (method == null) throw new IllegalStateException("Missing public API: " + target.getClass().getName() + "." + name);
        return call(method, target, args);
    }

    static Object invokeStatic(String owner, String name, Object... args) {
        try {
            Method method = find(type(owner), name, true, args);
            if (method == null) throw new IllegalStateException("Missing public API: " + owner + "." + name);
            return call(method, null, args);
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("Missing public API: " + owner, failure);
        }
    }

    private static Method find(Class<?> type, String name, boolean isStatic, Object[] args) {
        // Paper 的实现类可能不是 public；优先从公开接口取 Method，绝不 setAccessible。
        for (Class<?> api : type.getInterfaces()) {
            Method found = find(api, name, isStatic, args);
            if (found != null) return found;
        }
        if (Modifier.isPublic(type.getModifiers())) {
            for (Method method : type.getMethods()) {
                if (!Modifier.isPublic(method.getDeclaringClass().getModifiers())
                        || !method.getName().equals(name)
                        || Modifier.isStatic(method.getModifiers()) != isStatic
                        || method.getParameterTypes().length != args.length) continue;
                Class<?>[] parameters = method.getParameterTypes();
                boolean matches = true;
                for (int i = 0; i < args.length; i++) {
                    if (args[i] != null && !parameters[i].isInstance(args[i])) { matches = false; break; }
                }
                if (matches) return method;
            }
        }
        return type.getSuperclass() == null ? null : find(type.getSuperclass(), name, isStatic, args);
    }

    private static Object call(Method method, Object target, Object[] args) {
        try {
            return method.invoke(target, args);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot access public API: " + method, failure);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Public API failed: " + method, cause);
        }
    }
}
