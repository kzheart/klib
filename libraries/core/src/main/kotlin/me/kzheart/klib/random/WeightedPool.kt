package me.kzheart.klib.random

import java.util.Collections
import java.util.Random
import java.util.concurrent.ThreadLocalRandom
import java.util.function.ToDoubleFunction

/** Immutable weighted choices; callers never manage normalization or rounding. */
class WeightedPool<T> private constructor(values: List<T>, private val weights: DoubleArray) {
    private val values = Collections.unmodifiableList(values)
    private val cumulative = DoubleArray(weights.size)
    private val total: Double
    init {
        var sum = 0.0
        weights.forEachIndexed { index, weight -> sum += weight; cumulative[index] = sum }
        require(values.isEmpty() || sum > 0) { "Total weight must be positive" }
        require(!sum.isInfinite()) { "Total weight overflows double" }
        total = sum
    }
    @get:JvmName("emptyProperty")
    @get:JvmSynthetic
    val isEmpty: Boolean get() = isEmpty()
    fun isEmpty(): Boolean = values.isEmpty()
    fun totalWeight(): Double = total
    fun entries(): List<T> = values
    fun weight(index: Int): Double = weights[index]
    fun chance(index: Int): Double = weights[index] / total
    fun pick(): T = pick(ThreadLocalRandom.current())
    fun pick(random: Random): T {
        check(values.isNotEmpty()) { "WeightedPool is empty" }
        return values[indexOf(random.nextDouble() * total)]
    }
    fun pickDistinct(count: Int): List<T> = pickDistinct(count, ThreadLocalRandom.current())
    fun pickDistinct(count: Int, random: Random): List<T> {
        require(count >= 0) { "count must not be negative" }
        val remaining = weights.copyOf()
        var remainingTotal = total
        val picked = ArrayList<T>(minOf(count, values.size))
        while (picked.size < count && remainingTotal > 0) {
            var roll = random.nextDouble() * remainingTotal
            var chosen = -1
            var lastPositive = -1
            for (index in remaining.indices) {
                if (remaining[index] <= 0) continue
                lastPositive = index
                roll -= remaining[index]
                if (roll < 0) { chosen = index; break }
            }
            if (chosen < 0) chosen = lastPositive
            picked.add(values[chosen])
            remainingTotal -= remaining[chosen]
            remaining[chosen] = 0.0
            if (remainingTotal < 1.0E-12 * total) remainingTotal = remaining.filter { it > 0 }.sum()
        }
        return Collections.unmodifiableList(picked)
    }
    private fun indexOf(roll: Double): Int {
        var low = 0
        var high = cumulative.lastIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            if (roll < cumulative[middle]) high = middle else low = middle + 1
        }
        while (weights[low] <= 0 && low > 0) low--
        return low
    }
    class Builder<T> internal constructor() {
        private val values = mutableListOf<T>()
        private val weights = mutableListOf<Double>()
        fun add(value: T, weight: Double): Builder<T> {
            if (value == null) throw NullPointerException("value")
            values.add(value)
            weights.add(requireWeight(weight, value))
            return this
        }
        fun build(): WeightedPool<T> = WeightedPool(ArrayList(values), weights.toDoubleArray())
    }
    companion object {
        @JvmStatic fun <T> of(items: Collection<out T>, weightOf: ToDoubleFunction<in T>): WeightedPool<T> {
            val values = ArrayList<T>(items.size)
            val weights = DoubleArray(items.size)
            items.forEachIndexed { index, item ->
                if (item == null) throw NullPointerException("item")
                values.add(item)
                weights[index] = requireWeight(weightOf.applyAsDouble(item), item)
            }
            return WeightedPool(values, weights)
        }
        @JvmStatic fun <T> builder(): Builder<T> = Builder()
        private fun requireWeight(weight: Double, item: Any): Double {
            require(weight.isFinite() && weight >= 0) { "Invalid weight $weight for $item" }
            return weight
        }
    }
}
