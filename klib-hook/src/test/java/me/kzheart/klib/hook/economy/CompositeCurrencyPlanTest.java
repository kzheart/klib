package me.kzheart.klib.hook.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class CompositeCurrencyPlanTest {

    @Test
    void integerCurrencyShareIsFlooredAndTheRemainderMovesOn() {
        UnitCurrency points = new UnitCurrency("points", "10", BigDecimal.ONE, false);
        UnitCurrency cash = new UnitCurrency("cash", "10", null, false);
        CompositeCurrency composite = new CompositeCurrency(
                "combined", Arrays.<Currency>asList(points, cash));

        CurrencyResult result = composite.take(player(), new BigDecimal("3.5"));

        assertTrue(result.success());
        assertEquals(0, points.taken.compareTo(new BigDecimal("3")));
        assertEquals(0, cash.taken.compareTo(new BigDecimal("0.5")));
    }

    @Test
    void unrepresentableRemainderFailsWithoutDebitingAnything() {
        UnitCurrency first = new UnitCurrency("first", "10", BigDecimal.ONE, false);
        UnitCurrency second = new UnitCurrency("second", "10", BigDecimal.ONE, false);
        CompositeCurrency composite = new CompositeCurrency(
                "combined", Arrays.<Currency>asList(first, second));

        CurrencyResult result = composite.take(player(), new BigDecimal("3.5"));

        assertFalse(result.success());
        assertEquals(BigDecimal.ZERO, first.taken);
        assertEquals(BigDecimal.ZERO, second.taken);
    }

    @Test
    void rollbackFailureListsEveryUncompensatedDebit() {
        UnitCurrency first = new UnitCurrency("first", "2", null, true);
        UnitCurrency rejecting = new UnitCurrency("rejecting", "5", null, false);
        rejecting.rejectTake = true;
        CompositeCurrency composite = new CompositeCurrency(
                "combined", Arrays.<Currency>asList(first, rejecting));

        CurrencyResult result = composite.take(player(), new BigDecimal("4"));

        assertFalse(result.success());
        assertFalse(result.compensated());
        assertEquals(1, result.uncompensated().size());
        assertEquals("first", result.uncompensated().get(0).currencyId());
        assertEquals(new BigDecimal("2"), result.uncompensated().get(0).amount());
    }

    private static Player player() {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(
                CompositeCurrencyPlanTest.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getUniqueId")) {
                        return id;
                    }
                    if (method.getReturnType().equals(Boolean.TYPE)) {
                        return Boolean.FALSE;
                    }
                    if (method.getReturnType().isPrimitive()) {
                        return Integer.valueOf(0);
                    }
                    return null;
                });
    }

    private static final class UnitCurrency implements Currency {
        private final String id;
        private final BigDecimal unit;
        private final boolean throwGive;
        private BigDecimal balance;
        private BigDecimal taken = BigDecimal.ZERO;
        private boolean rejectTake;

        private UnitCurrency(String id, String balance, BigDecimal unit, boolean throwGive) {
            this.id = id;
            this.balance = new BigDecimal(balance);
            this.unit = unit;
            this.throwGive = throwGive;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public BigDecimal balance(Player player) {
            return balance;
        }

        @Override
        public CurrencyResult take(Player player, BigDecimal amount) {
            if (rejectTake) {
                return CurrencyResult.failure(amount, "rejected");
            }
            balance = balance.subtract(amount);
            taken = taken.add(amount);
            return CurrencyResult.success(amount);
        }

        @Override
        public CurrencyResult give(Player player, BigDecimal amount) {
            if (throwGive) {
                throw new IllegalStateException("give failed");
            }
            balance = balance.add(amount);
            return CurrencyResult.success(amount);
        }

        @Override
        public String format(BigDecimal amount) {
            return amount.toPlainString();
        }

        @Override
        public BigDecimal minorUnit() {
            return unit;
        }
    }
}
