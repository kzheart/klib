package me.kzheart.klib.config;

import me.kzheart.klib.scope.Scope;

/** 为作用域配置能力打开一个可重新加载的配置源。 */
@FunctionalInterface
public interface ConfigDocumentProvider {
    ConfigSource open(Scope owner, String path);
}
