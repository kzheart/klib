package me.kzheart.klib.config;

/** 将一个配置节点转换为应用所需的值。 */
@FunctionalInterface
public interface ConfigConverter<T> {
    T convert(ConfigNode node);
}
