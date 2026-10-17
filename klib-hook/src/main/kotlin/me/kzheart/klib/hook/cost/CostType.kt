package me.kzheart.klib.hook.cost

/** 把配置条目冒号后的参数解析为 Cost；参数非法时抛出 IllegalArgumentException。 */
@FunctionalInterface
fun interface CostType { fun parse(argument: String): Cost }
