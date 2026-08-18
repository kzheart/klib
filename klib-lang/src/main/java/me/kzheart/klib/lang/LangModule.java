package me.kzheart.klib.lang;

import me.kzheart.klib.config.FileConfigSource;
import me.kzheart.klib.config.MigrationRunner;
import me.kzheart.klib.config.YamlConfigCapability;
import me.kzheart.klib.config.YamlConfigMapper;
import me.kzheart.klib.config.api.ConfigDocument;
import me.kzheart.klib.scheduler.KScheduler;
import me.kzheart.klib.scheduler.SchedulerFactory;
import me.kzheart.klib.scope.Scope;
import org.bukkit.Bukkit;
import org.bukkit.Server;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * 语言模块入口：把消息目录、配置文档、消息管线和 Bukkit 路由器安装到给定作用域。
 *
 * <p>安装结果既作为返回值交给调用方，也会以 {@link LangRuntime} 能力注册到该作用域，
 * 便于其他模块用 {@code scope.requireCapability(LangRuntime.class)} 获取。
 * 同一作用域安装多个地区实例时，能力指向首个安装的运行时。</p>
 */
public final class LangModule {
    public static final String DEFAULT_LOCALE = "zh_CN";

    private static final Logger LOGGER = Logger.getLogger(LangModule.class.getName());

    private LangModule() {
    }

    public static LangRuntime install(
            Scope scope,
            Path dataDirectory,
            ClassLoader classLoader,
            String locale
    ) {
        Server server = Bukkit.getServer();
        if (server == null) {
            throw new IllegalStateException("Bukkit server is not available");
        }
        return install(scope, server, dataDirectory, classLoader, locale, null);
    }

    public static LangRuntime install(
            Scope scope,
            Server server,
            Path dataDirectory,
            ClassLoader classLoader,
            String locale,
            PlaceholderApi placeholderApi
    ) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(classLoader, "classLoader");
        String selectedLocale = normalizeLocale(locale);
        String relativePath = "lang/" + selectedLocale + ".yml";
        if (classLoader.getResource(relativePath) == null) {
            // 完整回退：保持地区、提取文件和资源一致，
            // 从而避免把默认地区内容写入其他地区的文件。
            LOGGER.warning("找不到语言资源 " + relativePath
                    + "，地区 " + selectedLocale + " 已回退到默认地区 " + DEFAULT_LOCALE);
            selectedLocale = DEFAULT_LOCALE;
            relativePath = "lang/" + DEFAULT_LOCALE + ".yml";
        }
        String resourcePath = relativePath;
        Path file = dataDirectory.toAbsolutePath().normalize().resolve(relativePath).normalize();
        if (!file.startsWith(dataDirectory.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("locale resolves outside data directory");
        }

        YamlConfigMapper mapper = new YamlConfigMapper();
        YamlConfigCapability capability = new YamlConfigCapability(
                (owner, ignored) -> FileConfigSource.fromClasspath(
                        file,
                        classLoader,
                        resourcePath,
                        new MigrationRunner()),
                mapper);
        ConfigDocument<LangRuntime.LanguageFile> document = capability.load(
                scope,
                LangRuntime.LanguageFile.class,
                relativePath);
        final String effectiveLocale = selectedLocale;
        ReloadableMessageCatalog catalog =
                new ReloadableMessageCatalog(catalog(document.value(), effectiveLocale));
        KScheduler scheduler = scope.findCapability(SchedulerFactory.class)
                .map(factory -> factory.forScope(scope))
                .orElse(null);
        BukkitMessageRouter router = scope.install(new BukkitMessageRouter(server, scheduler));
        // 消息目录中的 common.prefix 条目（若存在）会作为 {prefix} 的展开内容。
        String prefix = catalog.find("common.prefix").orElse("");
        MessagePipeline pipeline = new MessagePipeline(catalog, prefix, placeholderApi, router);
        document.onChange(() -> {
            MessageCatalog replacement = catalog(document.value(), effectiveLocale);
            catalog.replace(replacement);
            pipeline.updatePrefix(replacement.find("common.prefix").orElse(""));
        });
        LangRuntime runtime = new LangRuntime(scope, effectiveLocale, document, catalog, pipeline);
        // 同一作用域允许安装多个地区实例，因此只在尚未注册时登记能力，避免重复注册抛异常。
        if (!scope.findCapability(LangRuntime.class).isPresent()) {
            scope.registerCapability(LangRuntime.class, runtime);
        }
        return runtime;
    }

    private static MessageCatalog catalog(LangRuntime.LanguageFile file, String locale) {
        Map<String, Object> messages = file.messages == null
                ? Collections.<String, Object>emptyMap()
                : file.messages;
        MessageCatalog configured = YamlMessageCatalogLoader.fromDecodedMap(messages);
        return new FallbackMessageCatalog(configured, BuiltinMessages.catalog(locale));
    }

    private static String normalizeLocale(String locale) {
        String value = locale == null || locale.trim().isEmpty()
                ? DEFAULT_LOCALE
                : locale.trim();
        if (!value.matches("[A-Za-z]{2,8}([_-][A-Za-z0-9]{2,8})?")) {
            throw new IllegalArgumentException("Invalid locale: " + locale);
        }
        String[] parts = value.replace('-', '_').split("_");
        if (parts.length == 1) {
            return parts[0].toLowerCase(Locale.ROOT);
        }
        return parts[0].toLowerCase(Locale.ROOT) + "_" + parts[1].toUpperCase(Locale.ROOT);
    }
}
