package me.kzheart.klib.hook.cost;

import java.util.Objects;

/** 一次 {@link Cost#take} 的结果及其退还动作。 */
public final class Charge {
    private static final Runnable NOTHING = () -> { };
    private static final Charge FREE = new Charge(true, "ok", NOTHING);

    private final boolean success;
    private final String message;
    private final Runnable refund;

    private Charge(boolean success, String message, Runnable refund) {
        this.success = success;
        this.message = message;
        this.refund = refund;
    }

    /** 扣除成功；退还动作失败时应抛出异常，由 {@link CostPlan} 汇总。 */
    public static Charge success(Runnable refund) {
        return new Charge(true, "ok", Objects.requireNonNull(refund, "refund"));
    }

    /** 没有实际扣除，例如权限检查。 */
    public static Charge free() {
        return FREE;
    }

    public static Charge failure(String message) {
        return new Charge(false, Objects.requireNonNull(message, "message"), NOTHING);
    }

    public boolean success() {
        return success;
    }

    public String message() {
        return message;
    }

    public void refund() {
        refund.run();
    }
}
