package me.kzheart.klib.hook.cost

import java.util.ArrayList
import java.util.Collections

/** CostPlan.charge 的结果。 */
internal class CostResultValue private constructor(private val success: Boolean, lines: List<CostLine>,
        private val failedCost: String?, private val message: String, refundFailures: List<String>) {
    private val lines: List<CostLine> = Collections.unmodifiableList(ArrayList(lines))
    private val refundFailures: List<String> = Collections.unmodifiableList(ArrayList(refundFailures))
    fun success(): Boolean = success
    fun lines(): List<CostLine> = lines
    fun failedCost(): String? = failedCost
    fun message(): String = message
    fun refundFailures(): List<String> = refundFailures
    fun compensated(): Boolean = refundFailures.isEmpty()

    companion object {
        @JvmStatic fun success(lines: List<CostLine>): CostResultValue = CostResultValue(true, lines, null, "ok", emptyList())
        @JvmStatic fun unsatisfied(lines: List<CostLine>): CostResultValue {
            val line = lines.firstOrNull { !it.satisfied() } ?: throw IllegalArgumentException("No unsatisfied cost")
            return CostResultValue(false, lines, line.description(), line.error() ?: "unsatisfied", emptyList())
        }
        @JvmStatic fun takeFailed(lines: List<CostLine>, failedCost: String, message: String,
                                   refundFailures: List<String>): CostResultValue =
            CostResultValue(false, lines, failedCost, message, refundFailures)
    }
}
