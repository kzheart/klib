package me.kzheart.klib.random

import java.util.Random
import java.util.concurrent.ThreadLocalRandom

class DoubleRange private constructor(private val min: Double, private val max: Double) {
    init {
        require(min.isFinite() && max.isFinite()) { "Range bounds must be finite: $min, $max" }
        require(min <= max) { "Range minimum $min is greater than maximum $max" }
    }
    fun min(): Double = min
    fun max(): Double = max
    fun contains(value: Double): Boolean = value >= min && value <= max
    fun clamp(value: Double): Double = Math.max(min, Math.min(max, value))
    fun random(): Double = random(ThreadLocalRandom.current())
    fun random(random: Random): Double = if (min == max) min else min + random.nextDouble() * (max - min)
    override fun equals(other: Any?): Boolean = this === other || other is DoubleRange &&
        java.lang.Double.compare(min, other.min) == 0 && java.lang.Double.compare(max, other.max) == 0
    override fun hashCode(): Int = 31 * min.hashCode() + max.hashCode()
    override fun toString(): String = if (min == max) min.toString() else "$min~$max"
    companion object {
        private const val NUMBER = """[+-]?(?:\d+(?:\.\d*)?|\.\d+)"""
        private val range = Regex("""\s*($NUMBER)\s*(?:(?:~|-|to)\s*($NUMBER)\s*)?""")
        @JvmStatic fun of(min: Double, max: Double): DoubleRange = DoubleRange(min, max)
        @JvmStatic fun exactly(value: Double): DoubleRange = DoubleRange(value, value)
        @JvmStatic fun parse(text: String): DoubleRange {
            val match = range.matchEntire(text) ?: throw IllegalArgumentException(
                "Invalid number range: '$text', expected 1.5, 1-5 or 0.5~2.5")
            val left = IntRange.stripPlus(match.groupValues[1]).toDouble()
            val right = match.groups[2]?.value?.let { IntRange.stripPlus(it).toDouble() } ?: left
            return DoubleRange(left, right)
        }
    }
}
