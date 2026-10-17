package me.kzheart.klib.hook.cost

import java.math.BigDecimal
import java.util.ArrayList
import java.util.Objects
import java.util.function.BiPredicate
import java.util.function.Function
import java.util.function.Predicate
import me.kzheart.klib.hook.economy.Currency
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/** 内置消耗类型。所有返回的消耗均在 Bukkit 主线程执行。 */
class CostTypes private constructor() {
    companion object {
        /** 金额需为正数；退款失败由 CostPlan 记录。 */
        @JvmStatic fun currency(currency: Currency, label: String): CostType = CostType { argument ->
            val amount = positiveAmount(argument)
            val description = label + " " + currency.format(amount)
            object : Cost {
                override fun describe(): String = description
                override fun test(player: Player): Boolean = currency.has(player, amount)
                override fun take(player: Player): Charge {
                    val taken = currency.take(player, amount)
                    if (!taken.success()) return Charge.failure(taken.message())
                    return Charge.success {
                        val returned = currency.give(player, amount)
                        check(returned.success()) { returned.message() }
                    }
                }
            }
        }

        /** 只检查不扣除的权限条件。 */
        @JvmStatic fun permission(): CostType = CostType { node -> check("权限 $node", Predicate { it.hasPermission(node) }) }
        @JvmStatic fun level(): CostType = CostType { argument ->
            val levels = positiveInt(argument)
            object : Cost {
                override fun describe(): String = "等级 $levels"
                override fun test(player: Player): Boolean = player.level >= levels
                override fun take(player: Player): Charge {
                    player.level -= levels
                    return Charge.success { player.level += levels }
                }
            }
        }

        /** 背包物品消耗，引用*数量；匹配器在加载配置时解析。 */
        @JvmStatic fun items(matchers: Function<String, Predicate<ItemStack>>): CostType = items(matchers, Function.identity())
        @JvmStatic fun items(matchers: Function<String, Predicate<ItemStack>>, names: Function<String, String>): CostType =
            CostType { argument ->
                val reference = Entry.withoutAmount(argument)
                val amount = Entry.amountOf(argument)
                val matcher = Objects.requireNonNull(matchers.apply(reference), "matcher")
                val description = names.apply(reference) + " × " + amount
                object : Cost {
                    override fun describe(): String = description
                    override fun test(player: Player): Boolean = count(player.inventory, matcher) >= amount
                    override fun take(player: Player): Charge {
                        val removed = takeItems(player.inventory, matcher, amount)
                            ?: return Charge.failure("not enough items: $description")
                        return Charge.success { Inventories.give(player, removed) }
                    }
                }
            }

        /** 自定义只读条件，参数原样交给 condition。 */
        @JvmStatic fun condition(condition: BiPredicate<Player, String>): CostType = CostType { expression ->
            check(expression, Predicate { player -> condition.test(player, expression) })
        }

        private fun check(description: String, predicate: Predicate<Player>): Cost = object : Cost {
            override fun describe(): String = description
            override fun test(player: Player): Boolean = predicate.test(player)
            override fun take(player: Player): Charge = Charge.free()
        }

        @JvmStatic fun count(inventory: Inventory, matcher: Predicate<ItemStack>): Int {
            var total = 0
            for (item in inventory.storageContents) {
                if (!isAir(item) && matcher.test(item)) total += item.amount
            }
            return total
        }

        /** 数量不足时不修改背包；成功时返回被移除物品的副本。 */
        @JvmStatic fun takeItems(inventory: Inventory, matcher: Predicate<ItemStack>, amount: Int): List<ItemStack>? {
            val contents = inventory.storageContents
            var available = 0
            for (item in contents) if (!isAir(item) && matcher.test(item)) available += item.amount
            if (available < amount) return null
            val removed = ArrayList<ItemStack>()
            var remaining = amount
            for (slot in contents.indices) {
                if (remaining <= 0) break
                val item = contents[slot]
                if (isAir(item) || !matcher.test(item)) continue
                val consumed = minOf(remaining, item.amount)
                remaining -= consumed
                val taken = item.clone()
                taken.amount = consumed
                removed.add(taken)
                contents[slot] = if (consumed == item.amount) null else item.clone().apply { this.amount = item.amount - consumed }
            }
            inventory.storageContents = contents
            return removed
        }

        @JvmStatic fun isAir(item: ItemStack?): Boolean = item == null || item.type == Material.AIR || item.amount <= 0

        private fun positiveAmount(argument: String): BigDecimal = try {
            val amount = BigDecimal(argument.trim { it <= ' ' })
            require(amount.signum() > 0) { "Amount must be positive: $argument" }
            amount
        } catch (failure: NumberFormatException) {
            throw IllegalArgumentException("Invalid amount: '$argument'", failure)
        }
        @JvmStatic fun positiveInt(argument: String): Int = try {
            val value = argument.trim { it <= ' ' }.toInt()
            require(value >= 1) { "Value must be positive: $argument" }
            value
        } catch (failure: NumberFormatException) {
            throw IllegalArgumentException("Invalid integer: '$argument'", failure)
        }
    }
}
