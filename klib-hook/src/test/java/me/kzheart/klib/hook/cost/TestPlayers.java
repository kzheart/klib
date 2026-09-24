package me.kzheart.klib.hook.cost;

import me.kzheart.klib.hook.economy.Currency;
import me.kzheart.klib.hook.economy.CurrencyResult;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 用动态代理模拟消耗与奖励会触碰的玩家状态。 */
final class TestPlayers {
    private TestPlayers() {
    }

    static final class State {
        final UUID id = UUID.randomUUID();
        final ItemStack[] contents = new ItemStack[36];
        final Set<String> permissions = new HashSet<String>();
        final List<String> messages = new ArrayList<String>();
        final List<String> commands = new ArrayList<String>();
        final List<Boolean> opDuringCommand = new ArrayList<Boolean>();
        int level;
        boolean op;
        Player player;

        int count(org.bukkit.Material material) {
            int total = 0;
            for (ItemStack item : contents) {
                if (item != null && item.getType() == material) {
                    total += item.getAmount();
                }
            }
            return total;
        }
    }

    static State player() {
        State state = new State();
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(TestPlayers.class.getClassLoader(),
                new Class<?>[]{PlayerInventory.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "getStorageContents":
                            return Arrays.copyOf(state.contents, state.contents.length);
                        case "setStorageContents":
                            ItemStack[] next = (ItemStack[]) arguments[0];
                            System.arraycopy(next, 0, state.contents, 0, state.contents.length);
                            return null;
                        case "addItem":
                            for (ItemStack item : (ItemStack[]) arguments[0]) {
                                for (int slot = 0; slot < state.contents.length; slot++) {
                                    if (state.contents[slot] == null) {
                                        state.contents[slot] = item.clone();
                                        break;
                                    }
                                }
                            }
                            return new HashMap<Integer, ItemStack>();
                        default:
                            return null;
                    }
                });
        state.player = (Player) Proxy.newProxyInstance(TestPlayers.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "getInventory":
                            return inventory;
                        case "getLevel":
                            return state.level;
                        case "setLevel":
                            state.level = (Integer) arguments[0];
                            return null;
                        case "hasPermission":
                            return state.permissions.contains(String.valueOf(arguments[0]));
                        case "getName":
                            return "Steve";
                        case "getUniqueId":
                            return state.id;
                        case "sendMessage":
                            state.messages.add(String.valueOf(arguments[0]));
                            return null;
                        case "performCommand":
                            state.commands.add(String.valueOf(arguments[0]));
                            state.opDuringCommand.add(state.op);
                            return Boolean.TRUE;
                        case "isOp":
                            return state.op;
                        case "setOp":
                            state.op = (Boolean) arguments[0];
                            return null;
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == arguments[0];
                        default:
                            return null;
                    }
                });
        return state;
    }

    /** 余额在内存中的货币，可让扣款或退款强制失败。 */
    static final class MemoryCurrency implements Currency {
        BigDecimal balance;
        boolean failTake;
        boolean failGive;

        MemoryCurrency(String balance) {
            this.balance = new BigDecimal(balance);
        }

        @Override
        public String id() {
            return "memory";
        }

        @Override
        public BigDecimal balance(Player player) {
            return balance;
        }

        @Override
        public CurrencyResult take(Player player, BigDecimal amount) {
            if (failTake) {
                return CurrencyResult.failure(amount, "bank offline");
            }
            balance = balance.subtract(amount);
            return CurrencyResult.success(amount);
        }

        @Override
        public CurrencyResult give(Player player, BigDecimal amount) {
            if (failGive) {
                return CurrencyResult.failure(amount, "refund rejected");
            }
            balance = balance.add(amount);
            return CurrencyResult.success(amount);
        }

        @Override
        public String format(BigDecimal amount) {
            return amount.toPlainString();
        }
    }
}
