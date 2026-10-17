package me.kzheart.klib.config;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;

import me.kzheart.klib.KPlugin;
import me.kzheart.klib.scope.Scope;
import me.kzheart.klib.scope.capability.ConfigCapability;

/**
 * Config 模块入口：把类路径中的默认 YAML 提取到插件数据目录，
 * 并以可重新加载的 {@link ConfigCapability} 形式注册到作用域。
 *
 * <p>典型用法是在插件 {@code setup()} 中调用 {@code ConfigModule.install(this)}，
 * 之后通过 {@code configs().load(Settings.class, "config.yml")} 取得类型化文档。
 */
public final class ConfigModule {
    public static ConfigCapability install(KPlugin plugin) {
        return install(plugin, new YamlConfigMapper());
    }

    /** 使用显式配置的映射器；策略及转换器由调用者在安装前配置。 */
    public static ConfigCapability install(KPlugin plugin, YamlConfigMapper mapper) {
        Objects.requireNonNull(plugin, "plugin");
        return install(plugin.context().scope(), plugin.getDataFolder().toPath(),
                plugin.getClass().getClassLoader(), "defaults", mapper);
    }

    private ConfigModule() {
    }

    public static ConfigCapability install(
            Scope scope,
            Path dataDirectory,
            ClassLoader classLoader,
            String defaultsRoot
    ) {
        return install(scope, dataDirectory, classLoader, defaultsRoot, new YamlConfigMapper());
    }

    /** 自定义目录与映射器，不安装迁移步骤。 */
    public static ConfigCapability install(
            Scope scope,
            Path dataDirectory,
            ClassLoader classLoader,
            String defaultsRoot,
            YamlConfigMapper mapper
    ) {
        return install(scope, dataDirectory, classLoader, defaultsRoot, path -> new MigrationRunner(), mapper);
    }

    public static ConfigCapability install(
            Scope scope,
            Path dataDirectory,
            ClassLoader classLoader,
            String defaultsRoot,
            Function<String, MigrationRunner> migrations
    ) {
        return install(scope, dataDirectory, classLoader, defaultsRoot, migrations, new YamlConfigMapper());
    }

    /** 完整安装入口，所有文档共享此映射器及其未知键策略。 */
    public static ConfigCapability install(
            Scope scope,
            Path dataDirectory,
            ClassLoader classLoader,
            String defaultsRoot,
            Function<String, MigrationRunner> migrations,
            YamlConfigMapper mapper
    ) {
        Objects.requireNonNull(scope, "scope");
        final Path root = Objects.requireNonNull(dataDirectory, "dataDirectory")
                .toAbsolutePath().normalize();
        final ClassLoader loader = Objects.requireNonNull(classLoader, "classLoader");
        final String resourceRoot = normalizeRoot(defaultsRoot);
        final Function<String, MigrationRunner> migrationProvider =
                Objects.requireNonNull(migrations, "migrations");
        final YamlConfigMapper configuredMapper = Objects.requireNonNull(mapper, "mapper");

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
        }, configuredMapper);
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
