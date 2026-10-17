package me.kzheart.klib.hook.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class VaultCurrencyPrecisionTest {

    /** 设为 public，以便 ReflectiveCalls 能发现其方法。 */
    public static final class RecordingEconomy {
        int withdrawals;

        public boolean withdrawPlayer(Player player, double amount) {
            withdrawals++;
            return true;
        }

        public boolean depositPlayer(Player player, double amount) {
            return true;
        }

        public String format(double amount) {
            return String.valueOf(amount);
        }
    }

    @Test
    void rejectsAmountsThatLosePrecisionThroughDouble() {
        RecordingEconomy economy = new RecordingEconomy();
        VaultCurrency currency = new VaultCurrency(economy);
        BigDecimal amount = new BigDecimal("1.00000000000000001");

        CurrencyResult result = currency.take(player(), amount);

        assertFalse(result.success());
        assertTrue(result.message().contains("double"));
        assertEquals(0, economy.withdrawals);
        assertThrows(IllegalArgumentException.class, () -> currency.format(amount));
    }

    @Test
    void exactAmountsStillGoThrough() {
        RecordingEconomy economy = new RecordingEconomy();
        VaultCurrency currency = new VaultCurrency(economy);

        CurrencyResult result = currency.take(player(), new BigDecimal("0.5"));

        assertTrue(result.success());
        assertEquals(1, economy.withdrawals);
        assertEquals("-2.5", currency.format(new BigDecimal("-2.5")));
    }

    private static Player player() {
        return (Player) Proxy.newProxyInstance(
                VaultCurrencyPrecisionTest.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, arguments) -> {
                    if (method.getReturnType().equals(Boolean.TYPE)) {
                        return Boolean.FALSE;
                    }
                    if (method.getReturnType().isPrimitive()) {
                        return Integer.valueOf(0);
                    }
                    return null;
                });
    }
}
