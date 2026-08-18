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

class CompositeCurrencyRollbackTest {

    @Test
    void rollsBackEveryCompletedDebitWhenALaterCurrencyRejectsPayment() {
        Player player = player();
        RecordingCurrency first = new RecordingCurrency("first", "5", false);
        RecordingCurrency second = new RecordingCurrency("second", "5", true);
        CompositeCurrency composite = new CompositeCurrency(
                "combined",
                Arrays.<Currency>asList(first, second));

        CurrencyResult result = composite.take(player, new BigDecimal("8"));

        assertFalse(result.success());
        assertTrue(result.compensated());
        assertEquals(new BigDecimal("5"), first.balance(player));
        assertEquals(new BigDecimal("5"), second.balance(player));
        assertEquals(new BigDecimal("5"), first.given);
    }

    @Test
    void doesNotDebitAnythingWhenCombinedBalanceIsInsufficient() {
        Player player = player();
        RecordingCurrency first = new RecordingCurrency("first", "2", false);
        RecordingCurrency second = new RecordingCurrency("second", "3", false);
        CompositeCurrency composite = new CompositeCurrency(
                "combined",
                Arrays.<Currency>asList(first, second));

        CurrencyResult result = composite.take(player, new BigDecimal("6"));

        assertFalse(result.success());
        assertEquals(BigDecimal.ZERO, first.taken);
        assertEquals(BigDecimal.ZERO, second.taken);
    }

    @Test
    void catchesDebitExceptionsAndContinuesRollbackAfterACompensationThrows() {
        Player player = player();
        RecordingCurrency first = new RecordingCurrency("first", "2", false, false, false);
        RecordingCurrency second = new RecordingCurrency("second", "2", false, false, true);
        RecordingCurrency third = new RecordingCurrency("third", "2", false, true, false);
        CompositeCurrency composite = new CompositeCurrency(
                "combined",
                Arrays.<Currency>asList(first, second, third));

        CurrencyResult result = composite.take(player, new BigDecimal("5"));

        assertFalse(result.success());
        assertFalse(result.compensated());
        assertEquals(new BigDecimal("2"), first.balance(player));
        assertEquals(new BigDecimal("2"), first.given);
        assertEquals(BigDecimal.ZERO, second.balance(player));
        assertEquals(BigDecimal.ZERO, third.taken);
    }

    private static Player player() {
        UUID id = UUID.randomUUID();
        return (Player) Proxy.newProxyInstance(
                CompositeCurrencyRollbackTest.class.getClassLoader(),
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

    private static final class RecordingCurrency implements Currency {
        private final String id;
        private final boolean rejectTake;
        private final boolean throwTake;
        private final boolean throwGive;
        private BigDecimal balance;
        private BigDecimal taken = BigDecimal.ZERO;
        private BigDecimal given = BigDecimal.ZERO;

        private RecordingCurrency(String id, String balance, boolean rejectTake) {
            this(id, balance, rejectTake, false, false);
        }

        private RecordingCurrency(
                String id,
                String balance,
                boolean rejectTake,
                boolean throwTake,
                boolean throwGive
        ) {
            this.id = id;
            this.balance = new BigDecimal(balance);
            this.rejectTake = rejectTake;
            this.throwTake = throwTake;
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
            if (throwTake) {
                throw new IllegalStateException("take failed");
            }
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
            given = given.add(amount);
            return CurrencyResult.success(amount);
        }

        @Override
        public String format(BigDecimal amount) {
            return amount.toPlainString();
        }
    }
}
