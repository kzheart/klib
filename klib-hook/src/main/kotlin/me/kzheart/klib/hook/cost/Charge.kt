package me.kzheart.klib.hook.cost

/** 一次 Cost.take 的结果及其退还动作。 */
class Charge private constructor(private val success: Boolean, private val message: String,
                                  private val refund: Runnable) {
    fun success(): Boolean = success
    fun message(): String = message
    fun refund() { refund.run() }

    companion object {
        private val NOTHING = Runnable { }
        private val FREE = Charge(true, "ok", NOTHING)
        /** 扣除成功；退还失败时应抛出异常，由 CostPlan 汇总。 */
        @JvmStatic fun success(refund: Runnable): Charge = Charge(true, "ok", refund)
        @JvmStatic fun free(): Charge = FREE
        @JvmStatic fun failure(message: String): Charge = Charge(false, message, NOTHING)
    }
}
