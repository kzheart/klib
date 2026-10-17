package me.kzheart.klib.hook.cost;

import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewardPlanTest {
    private final List<String> console = new ArrayList<String>();
    private final ConsoleCommandSender consoleSender = (ConsoleCommandSender) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{ConsoleCommandSender.class}, (p, m, a) -> null);
    private final Server server = (Server) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{Server.class}, (proxy, method, arguments) -> {
                if ("getConsoleSender".equals(method.getName())) {
                    return consoleSender;
                }
                if ("dispatchCommand".equals(method.getName())) {
                    console.add((String) arguments[1]);
                    return !((String) arguments[1]).startsWith("unknown");
                }
                return null;
            });
    private final TestPlayers.MemoryCurrency money = new TestPlayers.MemoryCurrency("0");
    private final Rewards rewards = Rewards.builder()
            .defaults(server)
            .type("money", RewardTypes.currency(money, "金币"))
            .type("item", RewardTypes.items((reference, amount) -> "NOTHING".equals(reference)
                    ? null : new ItemStack(Material.valueOf(reference), amount)))
            .build();

    @Test
    void grantsEveryRewardAndResolvesPlaceholders() {
        TestPlayers.State state = TestPlayers.player();
        RewardResult result = rewards.parse(Arrays.asList(
                "console:/give %player% diamond 1",
                "player:spawn",
                "msg:&a领取成功 %player%",
                "money:100",
                "item:GOLD_INGOT*3")).grant(state.player);

        assertTrue(result.success(), String.valueOf(result.failures()));
        assertEquals(5, result.granted());
        assertEquals(Arrays.asList("give Steve diamond 1"), console);
        assertEquals(Arrays.asList("spawn"), state.commands);
        assertEquals("§a领取成功 Steve", state.messages.get(0));
        assertEquals(new BigDecimal("100"), money.balance);
        assertEquals(3, state.count(Material.GOLD_INGOT));
    }

    @Test
    void failuresAreCollectedWithoutStoppingLaterRewards() {
        TestPlayers.State state = TestPlayers.player();
        RewardResult result = rewards.parse(
                "console:unknown command", "item:NOTHING", "msg:still delivered").grant(state.player);

        assertFalse(result.success());
        assertEquals(1, result.granted());
        assertEquals(2, result.failures().size());
        assertEquals("still delivered", state.messages.get(0));
    }

    @Test
    void opCommandGrantsOpOnlyDuringTheCommand() {
        TestPlayers.State state = TestPlayers.player();
        rewards.parse("op:fly | 飞行").grant(state.player);

        assertEquals(Arrays.asList(Boolean.TRUE), state.opDuringCommand);
        assertFalse(state.op);
        assertEquals(Arrays.asList("飞行"), rewards.parse("op:fly | 飞行").describe());
    }

    @Test
    void invalidRewardEntriesFailAtParseTime() {
        assertThrows(IllegalArgumentException.class, () -> rewards.parse("money:abc"));
        assertThrows(IllegalArgumentException.class, () -> rewards.parse("item:GOLD_INGOT*0"));
        assertThrows(IllegalArgumentException.class, () -> rewards.parse("teleport:spawn"));
    }
}
