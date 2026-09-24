package me.kzheart.klib.hook.cost;

import org.bukkit.entity.Player;

/** 一项奖励。{@link #grant} 会访问玩家状态，应在 Bukkit 主线程调用；失败时抛出异常。 */
public interface Reward {
    String describe();

    void grant(Player player);
}
