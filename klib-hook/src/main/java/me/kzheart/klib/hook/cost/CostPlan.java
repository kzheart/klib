package me.kzheart.klib.hook.cost;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 按顺序组合的一组消耗：先全部检查，再依次扣除，中途失败时逆序退还已扣部分。 */
public final class CostPlan {
    private static final CostPlan EMPTY = new CostPlan(Collections.<Cost>emptyList());

    private final List<Cost> costs;

    CostPlan(List<Cost> costs) {
        this.costs = Collections.unmodifiableList(new ArrayList<Cost>(costs));
    }

    public static CostPlan of(List<? extends Cost> costs) {
        Objects.requireNonNull(costs, "costs");
        List<Cost> copy = new ArrayList<Cost>(costs.size());
        for (Cost cost : costs) {
            copy.add(Objects.requireNonNull(cost, "cost"));
        }
        return new CostPlan(copy);
    }

    public static CostPlan empty() {
        return EMPTY;
    }

    public List<Cost> costs() {
        return costs;
    }

    public boolean isEmpty() {
        return costs.isEmpty();
    }

    /** 逐项检查，不修改玩家状态。 */
    public List<CostLine> check(Player player) {
        Objects.requireNonNull(player, "player");
        List<CostLine> lines = new ArrayList<CostLine>(costs.size());
        for (Cost cost : costs) {
            lines.add(checkOne(cost, player));
        }
        return Collections.unmodifiableList(lines);
    }

    public boolean test(Player player) {
        for (CostLine line : check(player)) {
            if (!line.satisfied()) {
                return false;
            }
        }
        return true;
    }

    public CostResult charge(Player player) {
        List<CostLine> lines = check(player);
        for (CostLine line : lines) {
            if (!line.satisfied()) {
                return CostResult.unsatisfied(lines);
            }
        }
        List<Cost> takenCosts = new ArrayList<Cost>(costs.size());
        List<Charge> charges = new ArrayList<Charge>(costs.size());
        for (Cost cost : costs) {
            Charge charge;
            try {
                charge = Objects.requireNonNull(cost.take(player), "charge");
            } catch (RuntimeException failure) {
                charge = Charge.failure(describe(failure));
            }
            if (!charge.success()) {
                return CostResult.takeFailed(lines, cost.describe(), charge.message(), refund(takenCosts, charges));
            }
            takenCosts.add(cost);
            charges.add(charge);
        }
        return CostResult.success(lines);
    }

    private static List<String> refund(List<Cost> takenCosts, List<Charge> charges) {
        List<String> failures = new ArrayList<String>();
        for (int index = charges.size() - 1; index >= 0; index--) {
            try {
                charges.get(index).refund();
            } catch (RuntimeException failure) {
                failures.add(takenCosts.get(index).describe() + ": " + describe(failure));
            }
        }
        return failures;
    }

    private static CostLine checkOne(Cost cost, Player player) {
        try {
            return new CostLine(cost.describe(), cost.test(player), null);
        } catch (RuntimeException failure) {
            return new CostLine(cost.describe(), false, describe(failure));
        }
    }

    static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? failure.getClass().getName() : message;
    }
}
