package me.kzheart.klib.lang

import me.kzheart.klib.config.FileConfigSource
import me.kzheart.klib.config.MigrationRunner
import me.kzheart.klib.config.YamlConfigCapability
import me.kzheart.klib.config.YamlConfigMapper
import me.kzheart.klib.scheduler.SchedulerFactory
import me.kzheart.klib.scope.Scope
import org.bukkit.Bukkit
import org.bukkit.Server
import java.nio.file.Path
import java.util.Locale
import java.util.logging.Logger

/** 将消息目录、配置文档、消息管线和 Bukkit 路由器安装到给定作用域。 */
class LangModule private constructor() {
    companion object {
        const val DEFAULT_LOCALE = "zh_CN"
        private val LOGGER = Logger.getLogger(LangModule::class.java.name)

        @JvmStatic fun install(scope: Scope, dataDirectory: Path, classLoader: ClassLoader, locale: String?): LangRuntime {
            val server = Bukkit.getServer() ?: throw IllegalStateException("Bukkit server is not available")
            return install(scope, server, dataDirectory, classLoader, locale, null)
        }

        @JvmStatic fun install(scope: Scope, server: Server, dataDirectory: Path, classLoader: ClassLoader,
                               locale: String?, placeholderApi: PlaceholderApi?): LangRuntime {
            var selectedLocale = normalizeLocale(locale)
            var relativePath = "lang/$selectedLocale.yml"
            if (classLoader.getResource(relativePath) == null) {
                LOGGER.warning("找不到语言资源 $relativePath，地区 $selectedLocale 已回退到默认地区 $DEFAULT_LOCALE")
                selectedLocale = DEFAULT_LOCALE
                relativePath = "lang/$DEFAULT_LOCALE.yml"
            }
            val resourcePath = relativePath
            val directory = dataDirectory.toAbsolutePath().normalize()
            val file = directory.resolve(relativePath).normalize()
            require(file.startsWith(directory)) { "locale resolves outside data directory" }
            val capability = YamlConfigCapability({ _, _ ->
                FileConfigSource.fromClasspath(file, classLoader, resourcePath, MigrationRunner())
            }, YamlConfigMapper())
            val document = capability.load(scope, LangRuntime.LanguageFile::class.java, relativePath)
            val effectiveLocale = selectedLocale
            val catalog = ReloadableMessageCatalog(catalog(document.value(), effectiveLocale))
            val scheduler = scope.findCapability(SchedulerFactory::class.java).map { it.forScope(scope) }.orElse(null)
            val router = scope.install(BukkitMessageRouter(server, scheduler))
            val pipeline = MessagePipeline(catalog, catalog.find("common.prefix").orElse(""), placeholderApi, router)
            document.onChange {
                val replacement = catalog(document.value(), effectiveLocale)
                catalog.replace(replacement)
                pipeline.updatePrefix(replacement.find("common.prefix").orElse(""))
            }
            val runtime = LangRuntime(scope, effectiveLocale, document, catalog, pipeline)
            if (!scope.findCapability(LangRuntime::class.java).isPresent) scope.registerCapability(LangRuntime::class.java, runtime)
            return runtime
        }

        private fun catalog(file: LangRuntime.LanguageFile, locale: String): MessageCatalog =
            FallbackMessageCatalog(YamlMessageCatalogLoader.fromDecodedMap(file.messages ?: emptyMap<String, Any>()),
                BuiltinMessages.catalog(locale))

        private fun normalizeLocale(locale: String?): String {
            val value = locale?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_LOCALE
            require(value.matches(Regex("[A-Za-z]{2,8}([_-][A-Za-z0-9]{2,8})?"))) { "Invalid locale: $locale" }
            val parts = value.replace('-', '_').split('_')
            return parts[0].lowercase(Locale.ROOT) + if (parts.size > 1) "_" + parts[1].uppercase(Locale.ROOT) else ""
        }
    }
}
