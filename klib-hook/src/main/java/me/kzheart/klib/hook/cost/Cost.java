package me.kzheart.klib.hook.cost;

import org.bukkit.entity.Player;

/**
 * 一项消耗或前置条件。{@link CostPlan} 保证只在全部 {@link #test} 通过后才调用 {@link #take}。
 *
 * <p>所有方法都会访问玩家状态，应在 Bukkit 主线程调用。
 */
public interface Cost {
    /** 面向玩家的描述，例如 {@code 金币 500}。 */
    String describe();

    boolean test(Player player);

    /** 执行扣除并返回可退还的结果；只检查不扣除的条件返回 {@link Charge#free()}。 */
    Charge take(Player player);
}
