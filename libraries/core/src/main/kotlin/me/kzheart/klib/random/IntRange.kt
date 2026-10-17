package me.kzheart.klib.random

import java.util.Random
import java.util.concurrent.ThreadLocalRandom

class IntRange private constructor(private val min: Int, private val max: Int) {
    init { require(min <= max) { "Range minimum $min is greater than maximum $max" } }
    fun min(): Int = min
    fun max(): Int = max
    fun contains(value: Int): Boolean = value in min..max
    fun clamp(value: Int): Int = value.coerceIn(min, max)
    fun random(): Int = random(ThreadLocalRandom.current())
    fun random(random: Random): Int {
        val span = max.toLong() - min.toLong() + 1
        if (span <= Int.MAX_VALUE) return min + random.nextInt(span.toInt())
        val offset = (random.nextDouble() * span).toLong()
        return (min + minOf(offset, span - 1)).toInt()
    }
    override fun equals(other: Any?): Boolean = this === other || other is IntRange && min == other.min && max == other.max
    override fun hashCode(): Int = 31 * min + max
    override fun toString(): String = if (min == max) min.toString() else "$min~$max"
    companion object {
        private val range = Regex("""\s*([+-]?\d+)\s*(?:(?:~|-|to)\s*([+-]?\d+)\s*)?""")
        @JvmStatic fun of(min: Int, max: Int): IntRange = IntRange(min, max)
        @JvmStatic fun exactly(value: Int): IntRange = IntRange(value, value)
        @JvmStatic fun parse(text: String): IntRange {
            val match = range.matchEntire(text) ?: throw IllegalArgumentException(
                "Invalid integer range: '$text', expected 5, 1-5 or 1~5")
            try {
                val left = stripPlus(match.groupValues[1]).toInt()
                val right = match.groups[2]?.value?.let { stripPlus(it).toInt() } ?: left
                return IntRange(left, right)
            } catch (overflow: NumberFormatException) {
                throw IllegalArgumentException("Integer range is out of bounds: '$text'", overflow)
            }
        }
        @JvmStatic fun stripPlus(value: String): String = value.removePrefix("+")
    }
}
