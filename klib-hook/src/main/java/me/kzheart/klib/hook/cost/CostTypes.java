package me.kzheart.klib.hook.cost;

import me.kzheart.klib.hook.economy.Currency;
import me.kzheart.klib.hook.economy.CurrencyResult;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

/** 内置消耗类型。 */
public final class CostTypes {
    private CostTypes() {
    }

    /**
     * 货币消耗，参数为金额，例如 {@code money:500}。描述为 {@code label + " " + currency.format(amount)}。
     * 退还调用 {@link Currency#give}，失败时计入 {@link CostResult#refundFailures()}。
     */
    public static CostType currency(Currency currency, String label) {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(label, "label");
        return argument -> {
            BigDecimal amount = positiveAmount(argument);
            String description = label + " " + currency.format(amount);
            return new Cost() {
                @Override
                public String describe() {
                    return description;
                }

                @Override
                public boolean test(Player player) {
                    return currency.has(player, amount);
                }

                @Override
                public Charge take(Player player) {
                    CurrencyResult taken = currency.take(player, amount);
                    if (!taken.success()) {
                        return Charge.failure(taken.message());
                    }
                    return Charge.success(() -> {
                        CurrencyResult returned = currency.give(player, amount);
                        if (!returned.success()) {
                            throw new IllegalStateException(returned.message());
                        }
                    });
                }
            };
        };
    }

    /** 只检查不扣除的权限条件，例如 {@code perm:shop.vip}。 */
    public static CostType permission() {
        return node -> check("权限 " + node, player -> player.hasPermission(node));
    }

    /** 经验等级消耗，例如 {@code level:30}。 */
    public static CostType level() {
        return argument -> {
            int levels = positiveInt(argument);
            return new Cost() {
                @Override
                public String describe() {
                    return "等级 " + levels;
                }

                @Override
                public boolean test(Player player) {
                    return player.getLevel() >= levels;
                }

                @Override
                public Charge take(Player player) {
                    player.setLevel(player.getLevel() - levels);
                    return Charge.success(() -> player.setLevel(player.getLevel() + levels));
                }
            };
        };
    }

    /**
     * 背包物品消耗，参数为 {@code 引用*数量}，例如 {@code item:mi:MATERIAL:SOUL_GEM*3}。
     * 引用由 {@code matchers} 在解析时转为匹配器，因此非法引用会在加载配置时报错。
     * 与 klib-item 组合：{@code CostTypes.items(externalItems::matcher)}。
     */
    public static CostType items(Function<String, Predicate<ItemStack>> matchers) {
        return items(matchers, Function.identity());
    }

    /** 同 {@link #items(Function)}，并用 {@code names} 把引用转为面向玩家的物品名。 */
    public static CostType items(Function<String, Predicate<ItemStack>> matchers, Function<String, String> names) {
        Objects.requireNonNull(matchers, "matchers");
        Objects.requireNonNull(names, "names");
        return argument -> {
            String reference = Entry.withoutAmount(argument);
            int amount = Entry.amountOf(argument);
            Predicate<ItemStack> matcher = Objects.requireNonNull(matchers.apply(reference), "matcher");
            String description = names.apply(reference) + " × " + amount;
            return new Cost() {
                @Override
                public String describe() {
                    return description;
                }

                @Override
                public boolean test(Player player) {
                    return count(player.getInventory(), matcher) >= amount;
                }

                @Override
                public Charge take(Player player) {
                    List<ItemStack> removed = takeItems(player.getInventory(), matcher, amount);
                    if (removed == null) {
                        return Charge.failure("not enough items: " + description);
                    }
                    return Charge.success(() -> Inventories.give(player, removed));
                }
            };
        };
    }

    /**
     * 只检查不扣除的自定义条件，参数原样交给 {@code condition}。默认描述为参数原文，建议配合
     * {@code " | 描述"} 使用。接入 klib-script 的示例见模块文档。
     */
    public static CostType condition(BiPredicate<Player, String> condition) {
        Objects.requireNonNull(condition, "condition");
        return expression -> check(expression, player -> condition.test(player, expression));
    }

    private static Cost check(String description, Predicate<Player> predicate) {
        return new Cost() {
            @Override
            public String describe() {
                return description;
            }

            @Override
            public boolean test(Player player) {
                return predicate.test(player);
            }

            @Override
            public Charge take(Player player) {
                return Charge.free();
            }
        };
    }

    static int count(Inventory inventory, Predicate<ItemStack> matcher) {
        int total = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (!isAir(item) && matcher.test(item)) {
                total += item.getAmount();
            }
        }
        return total;
    }

    /** 整体扣除，数量不足时不修改背包并返回 {@code null}；成功时返回被移除物品的副本。 */
    static List<ItemStack> takeItems(Inventory inventory, Predicate<ItemStack> matcher, int amount) {
        ItemStack[] contents = inventory.getStorageContents();
        int available = 0;
        for (ItemStack item : contents) {
            if (!isAir(item) && matcher.test(item)) {
                available += item.getAmount();
            }
        }
        if (available < amount) {
            return null;
        }
        List<ItemStack> removed = new ArrayList<ItemStack>();
        int remaining = amount;
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack item = contents[slot];
            if (isAir(item) || !matcher.test(item)) {
                continue;
            }
            int consumed = Math.min(remaining, item.getAmount());
            remaining -= consumed;
            ItemStack taken = item.clone();
            taken.setAmount(consumed);
            removed.add(taken);
            if (consumed == item.getAmount()) {
                contents[slot] = null;
            } else {
                ItemStack reduced = item.clone();
                reduced.setAmount(item.getAmount() - consumed);
                contents[slot] = reduced;
            }
        }
        inventory.setStorageContents(contents);
        return removed;
    }

    static boolean isAir(ItemStack item) {
        return item == null || item.getType() == Material.AIR || item.getAmount() <= 0;
    }

    private static BigDecimal positiveAmount(String argument) {
        try {
            BigDecimal amount = new BigDecimal(argument.trim());
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("Amount must be positive: " + argument);
            }
            return amount;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid amount: '" + argument + "'", failure);
        }
    }

    static int positiveInt(String argument) {
        try {
            int value = Integer.parseInt(argument.trim());
            if (value < 1) {
                throw new IllegalArgumentException("Value must be positive: " + argument);
            }
            return value;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Invalid integer: '" + argument + "'", failure);
        }
    }
}
