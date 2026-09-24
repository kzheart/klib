package me.kzheart.klib.hook.cost;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** {@link RewardPlan#grant} 的结果。 */
public final class RewardResult {
    private final int granted;
    private final List<String> failures;

    RewardResult(int granted, List<String> failures) {
        this.granted = granted;
        this.failures = Collections.unmodifiableList(new ArrayList<String>(failures));
    }

    public boolean success() {
        return failures.isEmpty();
    }

    public int granted() {
        return granted;
    }

    /** 每项失败的奖励描述与原因。 */
    public List<String> failures() {
        return failures;
    }
}
