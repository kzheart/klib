package me.kzheart.klib.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import me.kzheart.klib.config.api.ConfigDocument;
import me.kzheart.klib.scheduler.SchedulerFactory;
import me.kzheart.klib.scheduler.Ticks;
import me.kzheart.klib.scope.Scope;
import me.kzheart.klib.scope.capability.ConfigCapability;

/** 将 YAML 文档适配为核心模块中无循环依赖的配置能力。 */
public final class YamlConfigCapability implements ConfigCapability {
    private final ConfigDocumentProvider provider;
    private final YamlConfigMapper mapper;

    public YamlConfigCapability(ConfigDocumentProvider provider, YamlConfigMapper mapper) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** 创建确定性的内存加载器，适用于测试和内置默认配置。 */
    public static YamlConfigCapability inMemory(Map<String, String> documents) {
        Objects.requireNonNull(documents, "documents");
        final Map<String, String> snapshot = new LinkedHashMap<String, String>(documents);
        return new YamlConfigCapability((owner, path) -> {
            String content = snapshot.get(path);
            if (content == null) {
                throw new ConfigException(path + ":<root>: configuration document was not found");
            }
            return new InMemoryConfigSource(path, content);
        }, new YamlConfigMapper());
    }

    @Override
    public <T> ConfigDocument<T> load(Scope owner, Class<T> type, String path) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(path, "path");
        if (owner.isClosed()) {
            throw new IllegalStateException("Cannot load config into closed scope: " + owner.name());
        }
        ConfigSource source = provider.open(owner, path);
        if (source == null) {
            throw new ConfigException(path + ":<root>: provider returned null source");
        }
        return YamlConfigDocument.open(owner, source, mapper, type, listenerExecutor(owner));
    }

    private static Consumer<Runnable> listenerExecutor(Scope owner) {
        if (!owner.findCapability(SchedulerFactory.class).isPresent()) {
            return Runnable::run;
        }
        SchedulerFactory factory = owner.requireCapability(SchedulerFactory.class);
        return listener -> factory.forScope(owner).after(Ticks.of(0L), listener);
    }
}
