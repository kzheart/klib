package me.kzheart.klib.hook.cost

import java.util.ArrayList
import java.util.Collections

/** RewardPlan.grant 的结果。 */
class RewardResult internal constructor(private val granted: Int, failures: List<String>) {
    private val failures: List<String> = Collections.unmodifiableList(ArrayList(failures))
    fun success(): Boolean = failures.isEmpty()
    fun granted(): Int = granted
    fun failures(): List<String> = failures
}
