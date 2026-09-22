package me.kzheart.klib.reflect;

import java.lang.reflect.*;
import java.util.*;

/** Shared, deterministic declaration validation. Only public instance methods are supported. */
public final class Declarations {
    private Declarations() { }
    public static List<Method> methods(Class<?> type) {
        // Derive the package after shading; hard-coded Klib names can miss private declarations.
        String utilityPackage = Declarations.class.getPackage().getName();
        String libraryPrefix = utilityPackage.substring(0, utilityPackage.lastIndexOf('.') + 1);
        // Reject annotated non-public methods instead of silently ignoring them.
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                for (java.lang.annotation.Annotation a : m.getAnnotations()) {
                    String name = a.annotationType().getName();
                    if ((name.startsWith(libraryPrefix) || name.equals("org.bukkit.event.EventHandler"))
                            && (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers()))) {
                        throw invalid(m, "annotated method must be public and non-static");
                    }
                }
            }
        }
        List<Method> result = new ArrayList<Method>();
        for (Method m : type.getMethods()) {
            if (!m.isBridge() && !m.isSynthetic()) result.add(m);
        }
        result.sort(Comparator.comparing(Method::toGenericString));
        return result;
    }
    public static void voidMethod(Method method, int parameters) {
        if (method.getReturnType() != Void.TYPE || method.getParameterCount() != parameters
                || Modifier.isStatic(method.getModifiers())) {
            throw invalid(method, "expected void instance method with " + parameters + " parameters");
        }
    }
    public static IllegalArgumentException invalid(Method method, String reason) {
        return new IllegalArgumentException(method.toGenericString() + ": " + reason);
    }
    public static Object invoke(Object target, Method method, Object... args) {
        try {
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(method.toGenericString(), cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(method.toGenericString(), failure);
        }
    }
}
