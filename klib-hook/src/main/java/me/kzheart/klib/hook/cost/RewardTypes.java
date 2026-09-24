package me.kzheart.klib.hook.cost;

import me.kzheart.klib.hook.economy.Currency;
import me.kzheart.klib.hook.economy.CurrencyResult;
import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 内置奖励类型。命令与消息类型会替换 {@code %player%} 与 {@code %uuid%}，
 * 需要 PlaceholderAPI 等更多占位符时传入额外的解析函数。
 */
public final class RewardTypes {
    private static final BiFunction<Player, String, String> NO_PLACEHOLDERS = (player, text) -> text;

    private RewardTypes() {
    }

    /** 以控制台身份执行命令，例如 {@code console:give %player% diamond 1}。 */
    public static RewardType consoleCommand(Server server) {
        return consoleCommand(server, NO_PLACEHOLDERS);
    }

    public static RewardType consoleCommand(Server server, BiFunction<Player, String, String> placeholders) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(placeholders, "placeholders");
        return command -> reward("控制台命令 /" + strip(command), player -> {
            if (!server.dispatchCommand(server.getConsoleSender(), resolve(player, strip(command), placeholders))) {
                throw new IllegalStateException("Console command was not handled");
            }
        });
    }

    /** 以玩家身份执行命令。 */
    public static RewardType playerCommand() {
        return playerCommand(NO_PLACEHOLDERS);
    }

    public static RewardType playerCommand(BiFunction<Player, String, String> placeholders) {
        Objects.requireNonNull(placeholders, "placeholders");
        return command -> reward("玩家命令 /" + strip(command),
                player -> player.performCommand(resolve(player, strip(command), placeholders)));
    }

    /**
     * 临时授予 OP 后以玩家身份执行命令，执行完成后在 {@code finally} 中撤销。
     * 命令内容可被服主配置，仅应用于受信任的配置；能用控制台命令时优先用 {@link #consoleCommand}。
     */
    public static RewardType opCommand() {
        return opCommand(NO_PLACEHOLDERS);
    }

    public static RewardType opCommand(BiFunction<Player, String, String> placeholders) {
        Objects.requireNonNull(placeholders, "placeholders");
        return command -> reward("OP 命令 /" + strip(command), player -> {
            String resolved = resolve(player, strip(command), placeholders);
            if (player.isOp()) {
                player.performCommand(resolved);
                return;
            }
            player.setOp(true);
            try {
                player.performCommand(resolved);
            } finally {
                player.setOp(false);
            }
        });
    }

    /** 向玩家发送消息，支持 {@code &} 颜色代码。 */
    public static RewardType message() {
        return message(NO_PLACEHOLDERS);
    }

    public static RewardType message(BiFunction<Player, String, String> placeholders) {
        Objects.requireNonNull(placeholders, "placeholders");
        return text -> reward("消息", player -> player.sendMessage(
                ChatColor.translateAlternateColorCodes('&', resolve(player, text, placeholders))));
    }

    /** 发放货币，例如 {@code money:100}。 */
    public static RewardType currency(Currency currency, String label) {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(label, "label");
        return argument -> {
            BigDecimal amount;
            try {
                amount = new BigDecimal(argument.trim());
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException("Invalid amount: '" + argument + "'", failure);
            }
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("Amount must be positive: " + argument);
            }
            return reward(label + " " + currency.format(amount), player -> {
                CurrencyResult result = currency.give(player, amount);
                if (!result.success()) {
                    throw new IllegalStateException(result.message());
                }
            });
        };
    }

    /**
     * 发放物品，参数为 {@code 引用*数量}。{@code creator} 按引用和数量生成物品，返回 {@code null} 视为失败。
     * 与 klib-item 组合：{@code RewardTypes.items((ref, n) -> externalItems.create(ref, n).orElse(null))}。
     * 背包放不下的部分掉落在玩家脚下。
     */
    public static RewardType items(BiFunction<String, Integer, ItemStack> creator) {
        return items(creator, Function.identity());
    }

    public static RewardType items(BiFunction<String, Integer, ItemStack> creator, Function<String, String> names) {
        Objects.requireNonNull(creator, "creator");
        Objects.requireNonNull(names, "names");
        return argument -> {
            String reference = Entry.withoutAmount(argument);
            int amount = Entry.amountOf(argument);
            return reward(names.apply(reference) + " × " + amount, player -> {
                ItemStack item = creator.apply(reference, Integer.valueOf(amount));
                if (CostTypes.isAir(item)) {
                    throw new IllegalStateException("Item is unavailable: " + reference);
                }
                Inventories.give(player, Collections.singletonList(item.clone()));
            });
        };
    }

    private static Reward reward(String description, java.util.function.Consumer<Player> action) {
        return new Reward() {
            @Override
            public String describe() {
                return description;
            }

            @Override
            public void grant(Player player) {
                action.accept(Objects.requireNonNull(player, "player"));
            }
        };
    }

    private static String strip(String command) {
        String trimmed = command.trim();
        return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
    }

    private static String resolve(Player player, String text, BiFunction<Player, String, String> placeholders) {
        String builtin = text.replace("%player%", player.getName())
                .replace("%uuid%", player.getUniqueId().toString());
        return Objects.requireNonNull(placeholders.apply(player, builtin), "resolved text");
    }
}
