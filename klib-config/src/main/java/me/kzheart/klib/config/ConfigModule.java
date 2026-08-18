package me.kzheart.klib.config;

import me.kzheart.klib.scope.Scope;
import me.kzheart.klib.scope.capability.ConfigCapability;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;

/**
 * Config 模块入口：把类路径中的默认 YAML 提取到插件数据目录，
 * 并以可重新加载的 {@link me.kzheart.klib.scope.capability.ConfigCapability} 形式注册到作用域。
 *
 * <p>典型用法是在插件 {@code setup} 中调用
 * {@code ConfigModule.install(root, getDataFolder().toPath(), getClassLoader(), "defaults")}，
 * 之后通过 {@code root.config(Settings.class, "config.yml")} 取得类型化文档。
 */
public final class ConfigModule {
    private ConfigModule() {
    }

    public static ConfigCapability install(
            Scope scope,
            Path dataDirectory,
            ClassLoader classLoader,
            String defaultsRoot
    ) {
        return install(scope, dataDirectory, classLoader, defaultsRoot, path -> new MigrationRunner());
    }

    public static ConfigCapability install(
            Scope scope,
            Path dataDirectory,
            ClassLoader classLoader,
            String defaultsRoot,
            Function<String, MigrationRunner> migrations
    ) {
        Objects.requireNonNull(scope, "scope");
        final Path root = Objects.requireNonNull(dataDirectory, "dataDirectory")
                .toAbsolutePath().normalize();
        final ClassLoader loader = Objects.requireNonNull(classLoader, "classLoader");
        final String resourceRoot = normalizeRoot(defaultsRoot);
        final Function<String, MigrationRunner> migrationProvider =
                Objects.requireNonNull(migrations, "migrations");

        YamlConfigCapability capability = new YamlConfigCapability((owner, requestedPath) -> {
            String path = normalizePath(requestedPath);
            Path file = root.resolve(path).normalize();
            if (!file.startsWith(root)) {
                throw new ConfigException(requestedPath + ":<root>: path escapes the plugin data directory");
            }
            MigrationRunner runner = migrationProvider.apply(path);
            if (runner == null) {
                throw new ConfigException(path + ":<root>: migration provider returned null");
            }
            return FileConfigSource.fromClasspath(file, loader, resourceRoot + path, runner);
        }, new YamlConfigMapper());
        return scope.registerCapability(ConfigCapability.class, capability);
    }

    private static String normalizeRoot(String root) {
        String normalized = Objects.requireNonNull(root, "defaultsRoot").trim().replace('\\', '/');
        if (!normalized.isEmpty() && !normalized.endsWith("/")) {
            normalized += "/";
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static String normalizePath(String path) {
        String normalized = Objects.requireNonNull(path, "path").trim().replace('\\', '/');
        if (normalized.isEmpty() || normalized.startsWith("/")) {
            throw new ConfigException(path + ":<root>: config path must be relative and non-empty");
        }
        return normalized;
    }
}
