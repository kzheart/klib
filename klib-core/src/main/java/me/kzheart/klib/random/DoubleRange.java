package me.kzheart.klib.random;

import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 闭区间小数范围，写法与 {@link IntRange} 相同，数值可带小数。 */
public final class DoubleRange {
    private static final String NUMBER = "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)";
    private static final Pattern RANGE = Pattern.compile(
            "\\s*(" + NUMBER + ")\\s*(?:(?:~|-|to)\\s*(" + NUMBER + ")\\s*)?");

    private final double min;
    private final double max;

    private DoubleRange(double min, double max) {
        if (Double.isNaN(min) || Double.isNaN(max) || Double.isInfinite(min) || Double.isInfinite(max)) {
            throw new IllegalArgumentException("Range bounds must be finite: " + min + ", " + max);
        }
        if (min > max) {
            throw new IllegalArgumentException("Range minimum " + min + " is greater than maximum " + max);
        }
        this.min = min;
        this.max = max;
    }

    public static DoubleRange of(double min, double max) {
        return new DoubleRange(min, max);
    }

    public static DoubleRange exactly(double value) {
        return new DoubleRange(value, value);
    }

    public static DoubleRange parse(String text) {
        Objects.requireNonNull(text, "text");
        Matcher matcher = RANGE.matcher(text);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid number range: '" + text + "', expected 1.5, 1-5 or 0.5~2.5");
        }
        double left = Double.parseDouble(IntRange.stripPlus(matcher.group(1)));
        double right = matcher.group(2) == null ? left : Double.parseDouble(IntRange.stripPlus(matcher.group(2)));
        return new DoubleRange(left, right);
    }

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    public boolean contains(double value) {
        return value >= min && value <= max;
    }

    public double clamp(double value) {
        return Math.max(min, Math.min(max, value));
    }

    /** 在 {@code [min, max)} 内均匀取值；上下界相等时返回该值。 */
    public double random() {
        return random(ThreadLocalRandom.current());
    }

    public double random(Random random) {
        Objects.requireNonNull(random, "random");
        if (min == max) {
            return min;
        }
        return min + random.nextDouble() * (max - min);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DoubleRange)) {
            return false;
        }
        DoubleRange that = (DoubleRange) other;
        return Double.compare(min, that.min) == 0 && Double.compare(max, that.max) == 0;
    }

    @Override
    public int hashCode() {
        return 31 * Double.hashCode(min) + Double.hashCode(max);
    }

    @Override
    public String toString() {
        return min == max ? Double.toString(min) : min + "~" + max;
    }
}
