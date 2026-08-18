package me.kzheart.klib.ui;

/** 从已解析的 YAML 菜单模板中解析物品标识符。 */
@FunctionalInterface
public interface YamlItemResolver {
    MenuEntry resolve(String id);
}
