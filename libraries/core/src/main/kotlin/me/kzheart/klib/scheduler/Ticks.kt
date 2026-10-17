package me.kzheart.klib.scheduler

import java.time.Duration

class Ticks private constructor(private val value: Long) {
    init { require(value >= 0) { "ticks must not be negative" } }
    fun value(): Long = value
    fun toMillis(): Long = Math.multiplyExact(value, MILLIS_PER_TICK)
    override fun equals(other: Any?): Boolean = this === other || other is Ticks && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "$value ticks"

    companion object {
        private const val MILLIS_PER_TICK = 50L
        @JvmStatic fun of(value: Long): Ticks = Ticks(value)
        @JvmStatic fun seconds(seconds: Long): Ticks {
            require(seconds >= 0) { "seconds must not be negative" }
            return Ticks(Math.multiplyExact(seconds, 20L))
        }
        @JvmStatic fun duration(duration: Duration): Ticks {
            require(!duration.isNegative) { "duration must not be negative" }
            return Ticks(Math.addExact(duration.toMillis(), MILLIS_PER_TICK - 1) / MILLIS_PER_TICK)
        }
    }
}
