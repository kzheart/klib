package me.kzheart.klib.config

import java.nio.file.Path
import java.util.function.Function
import me.kzheart.klib.KPlugin
import me.kzheart.klib.scope.Scope
import me.kzheart.klib.scope.capability.ConfigCapability

/** Installs a shared mapper and confines every source path to the plugin data directory. */
class ConfigModule private constructor() {
    companion object {
        @JvmStatic fun install(plugin: KPlugin): ConfigCapability = install(plugin, YamlConfigMapper())
        @JvmStatic fun install(plugin: KPlugin, mapper: YamlConfigMapper): ConfigCapability =
            install(plugin.context().scope(), plugin.dataFolder.toPath(), plugin.javaClass.classLoader, "defaults", mapper)
        @JvmStatic fun install(scope: Scope, dataDirectory: Path, classLoader: ClassLoader, defaultsRoot: String): ConfigCapability =
            install(scope, dataDirectory, classLoader, defaultsRoot, YamlConfigMapper())
        @JvmStatic fun install(scope: Scope, dataDirectory: Path, classLoader: ClassLoader, defaultsRoot: String, mapper: YamlConfigMapper): ConfigCapability =
            install(scope, dataDirectory, classLoader, defaultsRoot, Function { MigrationRunner() }, mapper)
        @JvmStatic fun install(scope: Scope, dataDirectory: Path, classLoader: ClassLoader, defaultsRoot: String,
            migrations: Function<String, MigrationRunner>): ConfigCapability =
            install(scope, dataDirectory, classLoader, defaultsRoot, migrations, YamlConfigMapper())
        @JvmStatic fun install(scope: Scope, dataDirectory: Path, classLoader: ClassLoader, defaultsRoot: String,
            migrations: Function<String, MigrationRunner>, mapper: YamlConfigMapper): ConfigCapability {
            val root = dataDirectory.toAbsolutePath().normalize()
            val resourceRoot = defaultsRoot.trim().replace('\\', '/').let { if (it.isNotEmpty() && !it.endsWith('/')) "$it/" else it }.trimStart('/')
            val capability = YamlConfigCapability({ _, requestedPath ->
                val path = requestedPath.trim().replace('\\', '/')
                if (path.isEmpty() || path.startsWith('/')) throw ConfigException("$requestedPath:<root>: config path must be relative and non-empty")
                val file = root.resolve(path).normalize()
                if (!file.startsWith(root)) throw ConfigException("$requestedPath:<root>: path escapes the plugin data directory")
                val runner = migrations.apply(path) ?: throw ConfigException("$path:<root>: migration provider returned null")
                FileConfigSource.fromClasspath(file, classLoader, resourceRoot + path, runner)
            }, mapper)
            return scope.registerCapability(ConfigCapability::class.java, capability)
        }
    }
}
