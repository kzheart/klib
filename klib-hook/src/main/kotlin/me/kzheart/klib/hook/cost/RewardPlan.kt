package me.kzheart.klib.hook.cost

import java.util.ArrayList
import java.util.Collections
import java.util.Objects
import org.bukkit.entity.Player

/** 按顺序发放奖励；单项失败不中断后续发放，结果保留全部失败明细。 */
class RewardPlan internal constructor(rewards: List<Reward>) {
    private val rewards: List<Reward> = Collections.unmodifiableList(ArrayList(rewards))
    fun rewards(): List<Reward> = rewards
    fun isEmpty(): Boolean = rewards.isEmpty()
    fun describe(): List<String> = Collections.unmodifiableList(rewards.map { it.describe() })
    fun grant(player: Player): RewardResult {
        val failures = ArrayList<String>()
        for (reward in rewards) {
            try { reward.grant(player) } catch (failure: RuntimeException) {
                failures.add(reward.describe() + ": " + CostPlan.describe(failure))
            }
        }
        return RewardResult(rewards.size - failures.size, failures)
    }
    companion object {
        @JvmStatic fun of(rewards: List<Reward>): RewardPlan =
            RewardPlan(rewards.map { Objects.requireNonNull(it, "reward") })
    }
}
