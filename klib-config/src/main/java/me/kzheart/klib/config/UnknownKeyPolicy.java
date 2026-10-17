package me.kzheart.klib.config;

/** 类型化配置模型中未知字段的处理策略；自由键 Map 不受此策略限制。 */
public enum UnknownKeyPolicy {
    /** 保持默认行为：忽略未知字段，每个来源路径只警告一次。 */
    WARN,
    /** 拒绝候选配置，保留原来已发布的配置值。 */
    REJECT
}
