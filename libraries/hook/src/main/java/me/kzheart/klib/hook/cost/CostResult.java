package me.kzheart.klib.hook.cost;

import java.util.List;

/** CostPlan.charge 的结果；Java 声明保留可空平台返回类型。 */
public final class CostResult {
    private final CostResultValue state;
    private CostResult(CostResultValue state) { this.state = state; }

    static CostResult success(List<CostLine> lines) { return new CostResult(CostResultValue.success(lines)); }
    static CostResult unsatisfied(List<CostLine> lines) { return new CostResult(CostResultValue.unsatisfied(lines)); }
    static CostResult takeFailed(List<CostLine> lines, String failedCost, String message, List<String> refundFailures) {
        return new CostResult(CostResultValue.takeFailed(lines, failedCost, message, refundFailures));
    }

    public boolean success() { return state.success(); }
    public List<CostLine> lines() { return state.lines(); }
    public String failedCost() { return state.failedCost(); }
    public String message() { return state.message(); }
    public List<String> refundFailures() { return state.refundFailures(); }
    public boolean compensated() { return state.compensated(); }
}
