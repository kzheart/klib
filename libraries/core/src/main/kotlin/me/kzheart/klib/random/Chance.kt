package me.kzheart.klib.random

import java.util.Random
import java.util.concurrent.ThreadLocalRandom

class Chance private constructor() {
    companion object {
        @JvmStatic fun percent(percent: Double): Boolean = percent(ThreadLocalRandom.current(), percent)
        @JvmStatic fun percent(random: Random, percent: Double): Boolean = ratio(random, finite(percent) / 100.0)
        @JvmStatic fun ratio(ratio: Double): Boolean = ratio(ThreadLocalRandom.current(), ratio)
        @JvmStatic fun ratio(random: Random, ratio: Double): Boolean {
            finite(ratio)
            return when { ratio <= 0 -> false; ratio >= 1 -> true; else -> random.nextDouble() < ratio }
        }
        private fun finite(value: Double): Double {
            require(value.isFinite()) { "Chance must be finite: $value" }
            return value
        }
    }
}
