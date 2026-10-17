package me.kzheart.klib.hook.cost;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostPlanTest {
    private final TestPlayers.MemoryCurrency money = new TestPlayers.MemoryCurrency("1000");
    private final TestPlayers.MemoryCurrency points = new TestPlayers.MemoryCurrency("50");
    private final Costs costs = Costs.builder()
            .defaults()
            .type("money", CostTypes.currency(money, "金币"))
            .type("points", CostTypes.currency(points, "点券"))
            .type("item", CostTypes.items(CostPlanTest::matcher, reference -> reference.toLowerCase()))
            .type("check", CostTypes.condition((player, expression) -> {
                if ("boom".equals(expression)) {
                    throw new IllegalStateException("script failed");
                }
                return "yes".equals(expression);
            }))
            .build();

    private static Predicate<ItemStack> matcher(String reference) {
        Material material = Material.valueOf(reference);
        return item -> item.getType() == material;
    }

    @Test
    void chargesEveryCostWhenAllAreSatisfied() {
        TestPlayers.State state = TestPlayers.player();
        state.level = 40;
        state.permissions.add("waypoint.vip");
        state.contents[0] = new ItemStack(Material.DIAMOND, 2);
        state.contents[5] = new ItemStack(Material.DIAMOND, 5);

        CostPlan plan = costs.parse(Arrays.asList(
                "money:500", "points:20", "item:DIAMOND*4", "perm:waypoint.vip", "level:30", "check:yes"));
        CostResult result = plan.charge(state.player);

        assertTrue(result.success(), result.message());
        assertEquals(new BigDecimal("500"), money.balance);
        assertEquals(new BigDecimal("30"), points.balance);
        assertEquals(3, state.count(Material.DIAMOND));
        assertEquals(10, state.level);
    }

    @Test
    void unsatisfiedPlanChangesNothingAndNamesTheFirstMissingCost() {
        TestPlayers.State state = TestPlayers.player();
        state.contents[0] = new ItemStack(Material.DIAMOND, 1);

        CostResult result = costs.parse("money:500", "item:DIAMOND*3", "perm:waypoint.vip").charge(state.player);

        assertFalse(result.success());
        assertEquals("diamond × 3", result.failedCost());
        assertEquals(new BigDecimal("1000"), money.balance);
        assertEquals(1, state.count(Material.DIAMOND));
        List<CostLine> lines = result.lines();
        assertTrue(lines.get(0).satisfied());
        assertFalse(lines.get(1).satisfied());
        assertFalse(lines.get(2).satisfied());
    }

    @Test
    void failedTakeRefundsEarlierCostsInReverseOrder() {
        TestPlayers.State state = TestPlayers.player();
        state.contents[3] = new ItemStack(Material.EMERALD, 4);
        points.failTake = true;

        CostResult result = costs.parse("money:200", "item:EMERALD*4", "points:10").charge(state.player);

        assertFalse(result.success());
        assertEquals("点券 10", result.failedCost());
        assertEquals("bank offline", result.message());
        assertTrue(result.compensated());
        assertEquals(new BigDecimal("1000"), money.balance);
        assertEquals(4, state.count(Material.EMERALD));
    }

    @Test
    void refundFailuresAreReportedForManualHandling() {
        TestPlayers.State state = TestPlayers.player();
        money.failGive = true;
        points.failTake = true;

        CostResult result = costs.parse("money:200", "points:10").charge(state.player);

        assertFalse(result.compensated());
        assertEquals(1, result.refundFailures().size());
        assertTrue(result.refundFailures().get(0).startsWith("金币 200"));
    }

    @Test
    void conditionErrorsBecomeUnsatisfiedLines() {
        TestPlayers.State state = TestPlayers.player();
        CostResult result = costs.parse("check:boom | 需要完成主线").charge(state.player);

        assertFalse(result.success());
        assertEquals("需要完成主线", result.failedCost());
        assertEquals("script failed", result.message());
    }

    @Test
    void parseErrorsNameTheEntry() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> costs.parse("money:10", "gold:5"));
        assertTrue(unknown.getMessage().contains("#2 'gold:5'"), unknown.getMessage());
        assertThrows(IllegalArgumentException.class, () -> costs.parse("money:-1"));
        assertThrows(IllegalArgumentException.class, () -> costs.parse("money"));
        assertThrows(IllegalArgumentException.class, () -> costs.parse("item:DIAMOND*x"));
        assertThrows(IllegalArgumentException.class, () -> costs.parse("item:NOT_A_MATERIAL"));
        assertThrows(IllegalStateException.class, () -> Costs.builder().defaults().type("perm", CostTypes.level()));
    }

    @Test
    void describeOverrideKeepsBehaviour() {
        Cost cost = costs.parseOne("perm:vip.use | 需要 VIP");
        assertEquals("需要 VIP", cost.describe());
        TestPlayers.State state = TestPlayers.player();
        assertFalse(cost.test(state.player));
        state.permissions.add("vip.use");
        assertTrue(cost.test(state.player));
    }
}
