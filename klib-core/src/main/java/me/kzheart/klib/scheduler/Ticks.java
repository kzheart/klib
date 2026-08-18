package me.kzheart.klib.scheduler;

import java.time.Duration;

public final class Ticks {
    private static final long MILLIS_PER_TICK = 50L;

    private final long value;

    private Ticks(long value) {
        if (value < 0L) {
            throw new IllegalArgumentException("ticks must not be negative");
        }
        this.value = value;
    }

    public static Ticks of(long value) {
        return new Ticks(value);
    }

    public static Ticks seconds(long seconds) {
        if (seconds < 0L) {
            throw new IllegalArgumentException("seconds must not be negative");
        }
        return new Ticks(Math.multiplyExact(seconds, 20L));
    }

    public static Ticks duration(Duration duration) {
        if (duration == null) {
            throw new NullPointerException("duration");
        }
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        long millis = duration.toMillis();
        return new Ticks(Math.addExact(millis, MILLIS_PER_TICK - 1L) / MILLIS_PER_TICK);
    }

    public long value() {
        return value;
    }

    public long toMillis() {
        return Math.multiplyExact(value, MILLIS_PER_TICK);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof Ticks && value == ((Ticks) other).value;
    }

    @Override
    public int hashCode() {
        return Long.valueOf(value).hashCode();
    }

    @Override
    public String toString() {
        return value + " ticks";
    }
}
