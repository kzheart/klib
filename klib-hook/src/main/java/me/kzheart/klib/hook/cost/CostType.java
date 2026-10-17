package me.kzheart.klib.hook.cost;

/** 把配置条目冒号后的参数解析为 {@link Cost}；参数非法时抛出 {@link IllegalArgumentException}。 */
@FunctionalInterface
public interface CostType {
    Cost parse(String argument);
}
