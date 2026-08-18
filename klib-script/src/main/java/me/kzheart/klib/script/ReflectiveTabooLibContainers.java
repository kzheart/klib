package me.kzheart.klib.script;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Bukkit 与 TabooLib OpenContainer 的无链接反射适配。 */
final class ReflectiveTabooLibContainers {

    private static final String MAIN_SUFFIX = ".platform.BukkitPlugin";
    private static final String API_SUFFIX = ".common.OpenAPI";

    private ReflectiveTabooLibContainers() {
    }

    static TabooLibKetherInterop.ContainerDiscovery bukkit(String providerName) {
        return new BukkitDiscovery(providerName);
    }

    private static final class BukkitDiscovery
            implements TabooLibKetherInterop.ContainerDiscovery {

        private final String providerName;
        private final Map<Object, TabooLibKetherInterop.OpenContainer> cache =
                new IdentityHashMap<Object, TabooLibKetherInterop.OpenContainer>();

        private BukkitDiscovery(String providerName) {
            this.providerName = providerName;
        }

        @Override
        public synchronized List<? extends TabooLibKetherInterop.OpenContainer> discover() {
            Object pluginManager = invokeStatic("org.bukkit.Bukkit", "getPluginManager");
            Object plugins = invoke(pluginManager, "getPlugins");
            int length = Array.getLength(plugins);
            List<TabooLibKetherInterop.OpenContainer> result =
                    new ArrayList<TabooLibKetherInterop.OpenContainer>();
            for (int index = 0; index < length; index++) {
                Object plugin = Array.get(plugins, index);
                String name = String.valueOf(invoke(plugin, "getName"));
                if (!providerName.equals(name)
                        && Boolean.TRUE.equals(invoke(plugin, "isEnabled"))
                        && plugin.getClass().getName().endsWith(MAIN_SUFFIX)) {
                    TabooLibKetherInterop.OpenContainer container = cache.get(plugin);
                    if (container == null) {
                        container = new ReflectionContainer(plugin, name);
                        cache.put(plugin, container);
                    }
                    if (((ReflectionContainer) container).valid()) {
                        result.add(container);
                    }
                }
            }
            return result;
        }

        @Override
        public synchronized TabooLibKetherInterop.OpenContainer find(String name) {
            for (TabooLibKetherInterop.OpenContainer container : discover()) {
                if (container.name().equals(name)) {
                    return container;
                }
            }
            return null;
        }
    }

    private static final class ReflectionContainer
            implements TabooLibKetherInterop.OpenContainer {

        private final String name;
        private final Class<?> api;
        private final Method call;

        private ReflectionContainer(Object plugin, String name) {
            this.name = name;
            String main = plugin.getClass().getName();
            String apiName = apiName(main);
            Class<?> resolved = null;
            Method resolvedCall = null;
            try {
                resolved = Class.forName(apiName, false, plugin.getClass().getClassLoader());
                resolvedCall = resolved.getMethod("call", String.class, Object[].class);
            } catch (ClassNotFoundException ignored) {
                // 不是可调用的 TabooLib 容器。
            } catch (NoSuchMethodException ignored) {
                // 不是可调用的 TabooLib 容器。
            }
            this.api = resolved;
            this.call = resolvedCall;
        }

        private boolean valid() {
            return api != null && call != null;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public TabooLibKetherInterop.OpenResult call(String channel, Object... data) {
            if (!valid()) {
                return TabooLibKetherInterop.OpenResult.failed();
            }
            try {
                Object foreign = call.invoke(null, channel, data);
                return castResult(foreign);
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("Cannot call TabooLib OpenAPI in " + name, failure);
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                throw new IllegalStateException("TabooLib OpenAPI failed in " + name,
                        cause == null ? failure : cause);
            }
        }
    }

    static String apiName(String mainClass) {
        if (mainClass == null || !mainClass.endsWith(MAIN_SUFFIX)) {
            throw new IllegalArgumentException(
                    "TabooLib main class must end with " + MAIN_SUFFIX);
        }
        return mainClass.substring(0, mainClass.length() - MAIN_SUFFIX.length()) + API_SUFFIX;
    }

    static TabooLibKetherInterop.OpenResult castResult(Object source) {
        if (source == null) {
            return TabooLibKetherInterop.OpenResult.failed();
        }
        if (source instanceof TabooLibKetherInterop.OpenResult) {
            return (TabooLibKetherInterop.OpenResult) source;
        }
        Object successful = property(source, "successful");
        Object value = property(source, "value");
        return Boolean.TRUE.equals(successful)
                ? TabooLibKetherInterop.OpenResult.successful(value)
                : TabooLibKetherInterop.OpenResult.failed();
    }

    static Object property(Object source, String name) {
        String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        String booleanGetter = "is" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        try {
            return invoke(source, getter);
        } catch (IllegalStateException ignored) {
            try {
                return invoke(source, booleanGetter);
            } catch (IllegalStateException ignoredAgain) {
                Class<?> type = source.getClass();
                while (type != null) {
                    try {
                        Field field = type.getDeclaredField(name);
                        field.setAccessible(true);
                        return field.get(source);
                    } catch (NoSuchFieldException missing) {
                        type = type.getSuperclass();
                    } catch (IllegalAccessException failure) {
                        throw new IllegalStateException(failure);
                    }
                }
                throw ignoredAgain;
            }
        }
    }

    static void setProperty(Object source, String name, Object value) {
        String setter = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        try {
            invoke(source, setter, value);
            return;
        } catch (IllegalStateException ignored) {
            Class<?> type = source.getClass();
            while (type != null) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    field.set(source, value);
                    return;
                } catch (NoSuchFieldException missing) {
                    type = type.getSuperclass();
                } catch (IllegalAccessException failure) {
                    throw new IllegalStateException(failure);
                }
            }
            throw ignored;
        }
    }

    static Object invokeStatic(String type, String name, Object... arguments) {
        try {
            return invoke(Class.forName(type), null, name, arguments);
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException(type + " is not available", failure);
        }
    }

    static Object invoke(Object target, String name, Object... arguments) {
        return invoke(target.getClass(), target, name, arguments);
    }

    private static Object invoke(
            Class<?> type,
            Object target,
            String name,
            Object[] arguments
    ) {
        Method selected = null;
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name)
                    && compatible(method.getParameterTypes(), arguments)) {
                selected = method;
                break;
            }
        }
        if (selected == null) {
            throw new IllegalStateException(new NoSuchMethodException(type.getName() + '.' + name));
        }
        try {
            selected.setAccessible(true);
            return selected.invoke(target, arguments);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException(cause == null ? failure : cause);
        }
    }

    private static boolean compatible(Class<?>[] parameters, Object[] arguments) {
        if (parameters.length != arguments.length) {
            return false;
        }
        for (int index = 0; index < parameters.length; index++) {
            Object argument = arguments[index];
            if (argument != null && !boxed(parameters[index]).isInstance(argument)) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return type;
    }
}
