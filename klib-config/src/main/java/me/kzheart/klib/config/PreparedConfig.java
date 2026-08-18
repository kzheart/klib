package me.kzheart.klib.config;

/** 已解析的候选配置；仅在类型映射成功后才提交源变更。 */
public interface PreparedConfig {
    YamlDocument document();

    void commit();

    /** 稳定的语义指纹，用于合并重复的文件系统事件。 */
    default String revision() {
        return document().toYaml();
    }
}
