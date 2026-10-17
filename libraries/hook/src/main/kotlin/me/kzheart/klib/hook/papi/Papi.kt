package me.kzheart.klib.hook.papi

import java.util.Locale
import java.util.Objects
import java.util.function.Consumer
import java.util.logging.Level
import java.util.logging.Logger
import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope
import org.bukkit.plugin.Plugin

/** 由作用域持有的 PlaceholderAPI DSL 入口。 */
class Papi private constructor() {
    companion object {
        private val LOGGER = Logger.getLogger(Papi::class.java.name)

        @JvmStatic fun register(scope: Scope, registrar: PapiRegistrar, identifier: String,
                                configure: Consumer<in PapiDsl>): PapiRegistration {
            val normalized = normalizeIdentifier(identifier)
            val dsl = PapiDsl()
            configure.accept(dsl)
            val delegate = Objects.requireNonNull(registrar.register(normalized, dsl.build()),
                "registrar returned null registration")
            val registration = PapiRegistration(normalized, registrar.available(), delegate)
            return try { scope.install(registration) } catch (failure: RuntimeException) {
                registration.dispose()
                throw failure
            }
        }

        /** 缺失软依赖时注册空操作句柄，且不加载 BukkitPapiRegistrar。 */
        @JvmStatic fun registerBukkit(scope: Scope, plugin: Plugin, identifier: String,
                                      configure: Consumer<in PapiDsl>): PapiRegistration =
            register(scope, detectRegistrar(plugin), identifier, configure)

        @JvmStatic fun detectRegistrar(plugin: Plugin): PapiRegistrar = try {
            if (!plugin.server.pluginManager.isPluginEnabled("PlaceholderAPI")) noopRegistrar()
            else newBukkitRegistrar(plugin)
        } catch (failure: RuntimeException) {
            unavailable(failure)
        } catch (failure: LinkageError) {
            unavailable(failure)
        }

        private fun unavailable(failure: Throwable): PapiRegistrar {
            LOGGER.log(Level.WARNING, "PlaceholderAPI is present but unavailable; using noop registrar", failure)
            return noopRegistrar()
        }

        /** 不接触 PlaceholderAPI 且接受所有扩展的注册器。 */
        @JvmStatic fun noopRegistrar(): PapiRegistrar = object : PapiRegistrar {
            override fun register(identifier: String?, expansion: PapiExpansion?): Disposable = Disposable { }
            override fun available(): Boolean = false
        }

        private fun newBukkitRegistrar(plugin: Plugin): PapiRegistrar = BukkitPapiRegistrar(plugin)
        private fun normalizeIdentifier(identifier: String): String {
            val normalized = identifier.trim { it <= ' ' }.lowercase(Locale.ROOT)
            require(normalized.matches(Regex("[a-z0-9_]+"))) { "invalid PAPI identifier: $identifier" }
            return normalized
        }
    }
}
