package me.kzheart.klib.random;

import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/** 概率判定。小于等于 0 永不命中，大于等于上限必定命中。 */
public final class Chance {
    private Chance() {
    }

    /** 按百分比判定，{@code 12.5} 表示 12.5%。 */
    public static boolean percent(double percent) {
        return percent(ThreadLocalRandom.current(), percent);
    }

    public static boolean percent(Random random, double percent) {
        return ratio(random, requireFinite(percent) / 100.0D);
    }

    /** 按比例判定，{@code 0.125} 表示 12.5%。 */
    public static boolean ratio(double ratio) {
        return ratio(ThreadLocalRandom.current(), ratio);
    }

    public static boolean ratio(Random random, double ratio) {
        Objects.requireNonNull(random, "random");
        requireFinite(ratio);
        if (ratio <= 0.0D) {
            return false;
        }
        if (ratio >= 1.0D) {
            return true;
        }
        return random.nextDouble() < ratio;
    }

    private static double requireFinite(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("Chance must be finite: " + value);
        }
        return value;
    }
}
