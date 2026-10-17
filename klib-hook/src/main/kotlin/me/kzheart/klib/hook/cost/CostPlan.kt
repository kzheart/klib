package me.kzheart.klib.hook.cost

import java.util.ArrayList
import java.util.Collections
import java.util.Objects
import org.bukkit.entity.Player

/** 先全部检查，再依次扣除，中途失败时逆序退还已扣部分。 */
class CostPlan internal constructor(costs: List<Cost>) {
    private val costs: List<Cost> = Collections.unmodifiableList(ArrayList(costs))
    fun costs(): List<Cost> = costs
    fun isEmpty(): Boolean = costs.isEmpty()
    /** 逐项检查，不修改玩家状态。 */
    fun check(player: Player): List<CostLine> = Collections.unmodifiableList(costs.map { checkOne(it, player) })
    fun test(player: Player): Boolean = check(player).all { it.satisfied() }

    fun charge(player: Player): CostResult {
        val lines = check(player)
        if (lines.any { !it.satisfied() }) return CostResult.unsatisfied(lines)
        val takenCosts = ArrayList<Cost>(costs.size)
        val charges = ArrayList<Charge>(costs.size)
        for (cost in costs) {
            val charge = try { Objects.requireNonNull(cost.take(player), "charge") } catch (failure: RuntimeException) {
                Charge.failure(describe(failure))
            }
            if (!charge.success()) return CostResult.takeFailed(lines, cost.describe(), charge.message(), refund(takenCosts, charges))
            takenCosts.add(cost)
            charges.add(charge)
        }
        return CostResult.success(lines)
    }

    companion object {
        private val EMPTY = CostPlan(emptyList())
        @JvmStatic fun of(costs: List<Cost>): CostPlan = CostPlan(costs.map { Objects.requireNonNull(it, "cost") })
        @JvmStatic fun empty(): CostPlan = EMPTY
        private fun refund(takenCosts: List<Cost>, charges: List<Charge>): List<String> {
            val failures = ArrayList<String>()
            for (index in charges.indices.reversed()) {
                try { charges[index].refund() } catch (failure: RuntimeException) {
                    failures.add(takenCosts[index].describe() + ": " + describe(failure))
                }
            }
            return failures
        }
        private fun checkOne(cost: Cost, player: Player): CostLine = try {
            CostLine(cost.describe(), cost.test(player), null)
        } catch (failure: RuntimeException) {
            CostLine(cost.describe(), false, describe(failure))
        }
        @JvmStatic fun describe(failure: Throwable): String = failure.message?.takeIf { it.isNotEmpty() } ?: failure.javaClass.name
    }
}
