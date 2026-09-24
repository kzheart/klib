package me.kzheart.klib.random;

import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 闭区间整数范围，支持 {@code 5}、{@code 1-5}、{@code 1~5}、{@code -5~-1} 与 {@code -5-3} 写法。 */
public final class IntRange {
    private static final Pattern RANGE = Pattern.compile("\\s*([+-]?\\d+)\\s*(?:(?:~|-|to)\\s*([+-]?\\d+)\\s*)?");

    private final int min;
    private final int max;

    private IntRange(int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException("Range minimum " + min + " is greater than maximum " + max);
        }
        this.min = min;
        this.max = max;
    }

    public static IntRange of(int min, int max) {
        return new IntRange(min, max);
    }

    public static IntRange exactly(int value) {
        return new IntRange(value, value);
    }

    public static IntRange parse(String text) {
        Objects.requireNonNull(text, "text");
        Matcher matcher = RANGE.matcher(text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid integer range: '" + text + "', expected 5, 1-5 or 1~5");
        }
        try {
            int left = Integer.parseInt(stripPlus(matcher.group(1)));
            int right = matcher.group(2) == null ? left : Integer.parseInt(stripPlus(matcher.group(2)));
            return new IntRange(left, right);
        } catch (NumberFormatException overflow) {
            throw new IllegalArgumentException("Integer range is out of bounds: '" + text + "'", overflow);
        }
    }

    public int min() {
        return min;
    }

    public int max() {
        return max;
    }

    public boolean contains(int value) {
        return value >= min && value <= max;
    }

    public int clamp(int value) {
        return Math.max(min, Math.min(max, value));
    }

    public int random() {
        return random(ThreadLocalRandom.current());
    }

    public int random(Random random) {
        Objects.requireNonNull(random, "random");
        long span = (long) max - (long) min + 1L;
        if (span <= Integer.MAX_VALUE) {
            return min + random.nextInt((int) span);
        }
        long offset = (long) (random.nextDouble() * span);
        return (int) (min + Math.min(offset, span - 1L));
    }

    static String stripPlus(String value) {
        return value.startsWith("+") ? value.substring(1) : value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IntRange)) {
            return false;
        }
        IntRange that = (IntRange) other;
        return min == that.min && max == that.max;
    }

    @Override
    public int hashCode() {
        return 31 * min + max;
    }

    @Override
    public String toString() {
        return min == max ? Integer.toString(min) : min + "~" + max;
    }
}
