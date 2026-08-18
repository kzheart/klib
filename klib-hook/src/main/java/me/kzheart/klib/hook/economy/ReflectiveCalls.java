package me.kzheart.klib.hook.economy;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class ReflectiveCalls {

    /** 以 (类, 方法名, 参数类型指纹) 为键缓存查找结果，含未命中。 */
    private static final ConcurrentHashMap<MethodKey, Optional<Method>> METHODS =
            new ConcurrentHashMap<MethodKey, Optional<Method>>();

    private ReflectiveCalls() {
    }

    static Object invoke(Object target, String name, Object... arguments) {
        Method method = find(target.getClass(), name, arguments);
        if (method == null) {
            throw new IllegalStateException(
                    "Missing method " + target.getClass().getName() + "." + name);
        }
        try {
            return method.invoke(target, arguments);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot access " + method, failure);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException("Integration call failed: " + method, cause);
        }
    }

    static Object invokeEither(Object target, String first, String second, Object... arguments) {
        Method method = find(target.getClass(), first, arguments);
        return method == null ? invoke(target, second, arguments) : invoke(target, first, arguments);
    }

    static boolean hasMethod(Object target, String name, Object... arguments) {
        return find(target.getClass(), name, arguments) != null;
    }

    static boolean succeeded(Object result) {
        if (result == null) {
            return true;
        }
        if (result instanceof Boolean) {
            return ((Boolean) result).booleanValue();
        }
        if (hasMethod(result, "transactionSuccess")) {
            Object value = invoke(result, "transactionSuccess");
            return Boolean.TRUE.equals(value);
        }
        try {
            Field field = result.getClass().getField("transactionSuccess");
            return field.getBoolean(result);
        } catch (NoSuchFieldException failure) {
            throw new IllegalStateException("Unknown transaction result: " + result.getClass().getName(), failure);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot read transaction result", failure);
        }
    }

    private static Method find(Class<?> type, String name, Object[] arguments) {
        Class<?>[] fingerprint = new Class<?>[arguments.length];
        for (int index = 0; index < arguments.length; index++) {
            fingerprint[index] = arguments[index] == null ? null : arguments[index].getClass();
        }
        MethodKey key = new MethodKey(type, name, fingerprint);
        Optional<Method> cached = METHODS.get(key);
        if (cached == null) {
            cached = Optional.ofNullable(scan(type, name, arguments));
            METHODS.putIfAbsent(key, cached);
        }
        return cached.orElse(null);
    }

    private static Method scan(Class<?> type, String name, Object[] arguments) {
        for (Method method : type.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals(name)
                    && parameters.length == arguments.length
                    && compatible(parameters, arguments)) {
                return method;
            }
        }
        return null;
    }

    private static boolean compatible(Class<?>[] parameters, Object[] arguments) {
        for (int index = 0; index < parameters.length; index++) {
            Object argument = arguments[index];
            if (argument == null) {
                if (parameters[index].isPrimitive()) {
                    return false;
                }
            } else if (!boxed(parameters[index]).isInstance(argument)) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == Boolean.TYPE) {
            return Boolean.class;
        }
        if (type == Integer.TYPE) {
            return Integer.class;
        }
        if (type == Long.TYPE) {
            return Long.class;
        }
        if (type == Double.TYPE) {
            return Double.class;
        }
        if (type == Float.TYPE) {
            return Float.class;
        }
        if (type == Short.TYPE) {
            return Short.class;
        }
        if (type == Byte.TYPE) {
            return Byte.class;
        }
        if (type == Character.TYPE) {
            return Character.class;
        }
        return type;
    }

    private static final class MethodKey {
        private final Class<?> type;
        private final String name;
        private final Class<?>[] argumentTypes;

        private MethodKey(Class<?> type, String name, Class<?>[] argumentTypes) {
            this.type = type;
            this.name = name;
            this.argumentTypes = argumentTypes;
        }

        @Override
        public boolean equals(Object candidate) {
            if (this == candidate) {
                return true;
            }
            if (!(candidate instanceof MethodKey)) {
                return false;
            }
            MethodKey other = (MethodKey) candidate;
            return type == other.type
                    && name.equals(other.name)
                    && Arrays.equals(argumentTypes, other.argumentTypes);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * type.hashCode() + name.hashCode())
                    + Arrays.hashCode(argumentTypes);
        }
    }
}
