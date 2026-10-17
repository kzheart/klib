package me.kzheart.klib.hook.cost;

/** 把配置条目冒号后的参数解析为 {@link Reward}；参数非法时抛出 {@link IllegalArgumentException}。 */
@FunctionalInterface
public interface RewardType {
    Reward parse(String argument);
}
