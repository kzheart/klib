package me.kzheart.klib.config;

import me.kzheart.klib.scope.Disposable;

/** 单个 YAML 配置文档的可重新加载源。 */
public interface ConfigSource {
    String sourceName();

    PreparedConfig prepare();

    default YamlDocument load() {
        PreparedConfig prepared = prepare();
        prepared.commit();
        return prepared.document();
    }

    Disposable watch(Runnable listener);
}
