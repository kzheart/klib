package me.kzheart.klib.compat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 发现同一个 classpath 上打包的仓库内置版本实现，并据此装配 {@link CompatResolver}。
 *
 * <p>发现基于固定的实现类名列表和 {@code Class.forName}，没打包的实现直接跳过。这里不使用
 * {@code ServiceLoader}：内置实现集合是封闭且已知的，类名字符串常量会被 Klib Gradle 插件的
 * 类文件重定位一起改写，无需额外维护 {@code META-INF/services} 资源。
 *
 * <p>{@code klib-compat} 不依赖 Bukkit，因此版本字符串默认由调用方传入；
 * {@link #detectServerVersion()} 只做反射探测，类缺失时降级为 {@link Optional#empty()}。
 *
 * <p>发现过程每次都会重新反射查找，结果不缓存；建议在插件启用时装配一次并自行持有
 * {@link CompatResolver}。
 */
public final class CompatProviders {
    /** 仓库内置实现的类名，按基准版本从旧到新排列。 */
    private static final List<String> BUNDLED_IMPLEMENTATIONS = Collections.unmodifiableList(
            Arrays.asList(
                    "me.kzheart.klib.compat.v1_12.V1_12CompatImplementation",
                    "me.kzheart.klib.compat.v1_20.V1_20CompatImplementation",
                    "me.kzheart.klib.compat.v1_21.V1_21CompatImplementation",
                    "me.kzheart.klib.compat.v26.V26CompatImplementation"));

    private CompatProviders() {
    }

    /** 返回当前 classpath 上可用的内置实现；一个都没打包时返回空列表。 */
    public static List<CompatProvider> discover() {
        return discover(CompatProviders.class.getClassLoader());
    }

    /** 使用指定类加载器发现内置实现；一个都没打包时返回空列表。 */
    public static List<CompatProvider> discover(ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        List<CompatProvider> providers = new ArrayList<CompatProvider>();
        for (String className : BUNDLED_IMPLEMENTATIONS) {
            CompatProvider provider = instantiate(className, loader);
            if (provider != null) {
                providers.add(provider);
            }
        }
        return Collections.unmodifiableList(providers);
    }

    /** 内置实现的类名列表，可用于启动自检时说明期望打包哪些模块。 */
    public static List<String> bundledImplementationClassNames() {
        return BUNDLED_IMPLEMENTATIONS;
    }

    /**
     * 用发现到的内置实现装配解析器。
     *
     * @throws IllegalStateException 没有任何内置实现被打包
     */
    public static CompatResolver resolver() {
        return resolver(CompatProviders.class.getClassLoader());
    }

    /**
     * 用指定类加载器发现到的内置实现装配解析器。
     *
     * @throws IllegalStateException 没有任何内置实现被打包
     */
    public static CompatResolver resolver(ClassLoader loader) {
        List<CompatProvider> providers = discover(loader);
        if (providers.isEmpty()) {
            throw new IllegalStateException(
                    "No bundled klib compatibility implementation found on the classpath; "
                            + "expected at least one of " + BUNDLED_IMPLEMENTATIONS
                            + ". Add the matching klib-compat-v* module (Gradle 插件写法："
                            + "klib { modules(\"compat-v1_12\", ...) }).");
        }
        return new CompatResolver(providers);
    }

    /**
     * 一步完成发现与解析。
     *
     * @param serverVersion 服务端版本文本，例如 {@code Bukkit.getBukkitVersion()} 的返回值
     * @throws IllegalStateException 没有任何内置实现被打包
     * @throws IllegalArgumentException 版本无法解析，或没有实现覆盖该版本
     */
    public static CompatProvider resolve(String serverVersion) {
        return resolver().resolve(serverVersion);
    }

    /**
     * 反射读取 {@code org.bukkit.Bukkit.getBukkitVersion()}。
     *
     * <p>不在 Bukkit 运行时（单元测试、独立进程）或服务器尚未初始化时返回
     * {@link Optional#empty()}，不抛异常。
     */
    public static Optional<String> detectServerVersion() {
        try {
            Method method = Class.forName("org.bukkit.Bukkit").getMethod("getBukkitVersion");
            Object version = method.invoke(null);
            return version instanceof String
                    ? Optional.of((String) version)
                    : Optional.<String>empty();
        } catch (ReflectiveOperationException unavailable) {
            return Optional.empty();
        } catch (RuntimeException unavailable) {
            // 服务器尚未初始化时 Bukkit 会抛异常，这不是调用方的错误。
            return Optional.empty();
        }
    }

    /**
     * 发现内置实现并按当前 Bukkit 服务端版本解析，仅在 Bukkit 运行时可用。
     *
     * @throws IllegalStateException 没有任何内置实现被打包，或无法探测到服务端版本
     * @throws IllegalArgumentException 没有实现覆盖当前版本
     */
    public static CompatProvider resolveCurrent() {
        Optional<String> serverVersion = detectServerVersion();
        if (!serverVersion.isPresent()) {
            throw new IllegalStateException(
                    "Cannot detect the running server version; org.bukkit.Bukkit is unavailable. "
                            + "Call CompatProviders.resolve(String) with an explicit version instead.");
        }
        return resolve(serverVersion.get());
    }

    private static CompatProvider instantiate(String className, ClassLoader loader) {
        try {
            Class<?> type = Class.forName(className, true, loader);
            return type.asSubclass(CompatProvider.class).getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException notBundled) {
            return null;
        } catch (NoClassDefFoundError notBundled) {
            return null;
        } catch (ReflectiveOperationException broken) {
            throw new IllegalStateException(
                    "Cannot instantiate bundled compatibility implementation " + className, broken);
        }
    }
}
