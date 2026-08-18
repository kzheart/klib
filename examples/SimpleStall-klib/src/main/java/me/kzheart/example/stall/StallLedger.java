package me.kzheart.example.stall;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 确定性的测试夹具账本；生产环境的货币钩子可在单一边界替换它。 */
public final class StallLedger {
    private final Map<UUID, BigDecimal> balances = new HashMap<UUID, BigDecimal>();

    public synchronized void set(UUID playerId, BigDecimal balance) {
        balances.put(playerId, balance);
    }

    public synchronized BigDecimal balance(UUID playerId) {
        BigDecimal value = balances.get(playerId);
        return value == null ? BigDecimal.ZERO : value;
    }

    public synchronized boolean transfer(
            UUID buyerId,
            UUID sellerId,
            BigDecimal charged,
            BigDecimal sellerIncome
    ) {
        BigDecimal buyerBalance = balance(buyerId);
        if (buyerBalance.compareTo(charged) < 0) {
            return false;
        }
        balances.put(buyerId, buyerBalance.subtract(charged));
        balances.put(sellerId, balance(sellerId).add(sellerIncome));
        return true;
    }
}
