package me.kzheart.klib.hook.cost

/** 把配置条目冒号后的参数解析为 Reward；参数非法时抛出 IllegalArgumentException。 */
@FunctionalInterface
fun interface RewardType { fun parse(argument: String): Reward }
