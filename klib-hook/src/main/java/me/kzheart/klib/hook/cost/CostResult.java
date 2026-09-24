package me.kzheart.klib.hook.cost;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** {@link CostPlan#charge} 的结果。 */
public final class CostResult {
    private final boolean success;
    private final List<CostLine> lines;
    private final String failedCost;
    private final String message;
    private final List<String> refundFailures;

    private CostResult(boolean success, List<CostLine> lines, String failedCost, String message,
                       List<String> refundFailures) {
        this.success = success;
        this.lines = Collections.unmodifiableList(new ArrayList<CostLine>(lines));
        this.failedCost = failedCost;
        this.message = message;
        this.refundFailures = Collections.unmodifiableList(new ArrayList<String>(refundFailures));
    }

    static CostResult success(List<CostLine> lines) {
        return new CostResult(true, lines, null, "ok", Collections.<String>emptyList());
    }

    static CostResult unsatisfied(List<CostLine> lines) {
        for (CostLine line : lines) {
            if (!line.satisfied()) {
                return new CostResult(false, lines, line.description(),
                        line.error() == null ? "unsatisfied" : line.error(), Collections.<String>emptyList());
            }
        }
        throw new IllegalArgumentException("No unsatisfied cost");
    }

    static CostResult takeFailed(List<CostLine> lines, String failedCost, String message, List<String> refundFailures) {
        return new CostResult(false, lines, failedCost, message, refundFailures);
    }

    public boolean success() {
        return success;
    }

    /** 扣除前的检查结果，每项消耗一行。 */
    public List<CostLine> lines() {
        return lines;
    }

    /** 第一个不满足或扣除失败的消耗描述；成功时为 {@code null}。 */
    public String failedCost() {
        return failedCost;
    }

    public String message() {
        return message;
    }

    /** 已扣除但退还失败的消耗描述，需要人工处理。 */
    public List<String> refundFailures() {
        return refundFailures;
    }

    public boolean compensated() {
        return refundFailures.isEmpty();
    }
}
