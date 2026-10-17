package me.kzheart.klib.config;

/** 一次幂等的文档迁移。 */
@FunctionalInterface
public interface Migration {
    void apply(YamlDocument document);
}
