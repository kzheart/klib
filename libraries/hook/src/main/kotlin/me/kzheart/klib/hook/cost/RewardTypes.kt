package me.kzheart.klib.hook.cost

import java.math.BigDecimal
import java.util.Collections
import java.util.Objects
import java.util.function.BiFunction
import java.util.function.Consumer
import java.util.function.Function
import me.kzheart.klib.hook.economy.Currency
import org.bukkit.ChatColor
import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** 内置奖励类型。命令与消息替换 %player% 与 %uuid%，并支持额外的占位符解析。 */
class RewardTypes private constructor() {
    companion object {
        private val NO_PLACEHOLDERS = BiFunction<Player, String, String> { _, text -> text }

        @JvmStatic fun consoleCommand(server: Server): RewardType = consoleCommand(server, NO_PLACEHOLDERS)
        @JvmStatic fun consoleCommand(server: Server, placeholders: BiFunction<Player, String, String>): RewardType =
            RewardType { command -> reward("控制台命令 /" + strip(command), Consumer { player ->
                check(server.dispatchCommand(server.consoleSender, resolve(player, strip(command), placeholders))) {
                    "Console command was not handled"
                }
            }) }

        @JvmStatic fun playerCommand(): RewardType = playerCommand(NO_PLACEHOLDERS)
        @JvmStatic fun playerCommand(placeholders: BiFunction<Player, String, String>): RewardType =
            RewardType { command -> reward("玩家命令 /" + strip(command), Consumer { player ->
                player.performCommand(resolve(player, strip(command), placeholders))
            }) }

        /** 仅用于受信任的配置；临时 OP 权限在 finally 中撤销。 */
        @JvmStatic fun opCommand(): RewardType = opCommand(NO_PLACEHOLDERS)
        @JvmStatic fun opCommand(placeholders: BiFunction<Player, String, String>): RewardType =
            RewardType { command -> reward("OP 命令 /" + strip(command), Consumer { player ->
                val resolved = resolve(player, strip(command), placeholders)
                if (player.isOp) {
                    player.performCommand(resolved)
                } else {
                    player.isOp = true
                    try { player.performCommand(resolved) } finally { player.isOp = false }
                }
            }) }

        @JvmStatic fun message(): RewardType = message(NO_PLACEHOLDERS)
        @JvmStatic fun message(placeholders: BiFunction<Player, String, String>): RewardType =
            RewardType { text -> reward("消息", Consumer { player ->
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', resolve(player, text, placeholders)))
            }) }

        @JvmStatic fun currency(currency: Currency, label: String): RewardType = RewardType { argument ->
            val amount = try { BigDecimal(argument.trim { it <= ' ' }) } catch (failure: NumberFormatException) {
                throw IllegalArgumentException("Invalid amount: '$argument'", failure)
            }
            require(amount.signum() > 0) { "Amount must be positive: $argument" }
            reward(label + " " + currency.format(amount), Consumer { player ->
                val result = currency.give(player, amount)
                check(result.success()) { result.message() }
            })
        }

        /** 物品引用*数量；创建器返回 null 或空气时发放失败，溢出物品掉落到玩家脚下。 */
        @JvmStatic fun items(creator: BiFunction<String, Int, ItemStack>): RewardType = items(creator, Function.identity())
        @JvmStatic fun items(creator: BiFunction<String, Int, ItemStack>, names: Function<String, String>): RewardType =
            RewardType { argument ->
                val reference = Entry.withoutAmount(argument)
                val amount = Entry.amountOf(argument)
                reward(names.apply(reference) + " × " + amount, Consumer { player ->
                    val item = creator.apply(reference, amount)
                    check(!CostTypes.isAir(item)) { "Item is unavailable: $reference" }
                    Inventories.give(player, Collections.singletonList(item.clone()))
                })
            }

        private fun reward(description: String, action: Consumer<Player>): Reward = object : Reward {
            override fun describe(): String = description
            override fun grant(player: Player) { action.accept(player) }
        }
        private fun strip(command: String): String {
            val trimmed = command.trim { it <= ' ' }
            return if (trimmed.startsWith("/")) trimmed.substring(1) else trimmed
        }
        private fun resolve(player: Player, text: String, placeholders: BiFunction<Player, String, String>): String {
            val builtin = text.replace("%player%", player.name).replace("%uuid%", player.uniqueId.toString())
            return Objects.requireNonNull(placeholders.apply(player, builtin), "resolved text")
        }
    }
}
