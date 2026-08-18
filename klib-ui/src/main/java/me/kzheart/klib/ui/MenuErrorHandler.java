package me.kzheart.klib.ui;

import org.bukkit.entity.Player;

/** 菜单动作失败后调用、面向玩家的可配置错误边界。 */
@FunctionalInterface
public interface MenuErrorHandler {
    void onError(Player player, MenuModel model, MenuClick click, Throwable failure);
}
