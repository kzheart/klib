package me.kzheart.klib.random;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToDoubleFunction;

/**
 * 不可变的加权随机池。
 *
 * <p>构建时拒绝负数、NaN 和无穷权重；池非空但总权重为零时直接失败，而不是退化成某种猜测的行为。
 * 权重为零的条目保留在 {@link #entries()} 中，但永远不会被抽中。
 */
public final class WeightedPool<T> {
    private final List<T> values;
    private final double[] weights;
    private final double[] cumulative;
    private final double total;

    private WeightedPool(List<T> values, double[] weights) {
        this.values = Collections.unmodifiableList(values);
        this.weights = weights;
        this.cumulative = new double[weights.length];
        double sum = 0.0D;
        for (int index = 0; index < weights.length; index++) {
            sum += weights[index];
            cumulative[index] = sum;
        }
        if (!values.isEmpty() && !(sum > 0.0D)) {
            throw new IllegalArgumentException("Total weight must be positive");
        }
        if (Double.isInfinite(sum)) {
            throw new IllegalArgumentException("Total weight overflows double");
        }
        this.total = sum;
    }

    public static <T> WeightedPool<T> of(Collection<? extends T> items, ToDoubleFunction<? super T> weightOf) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(weightOf, "weightOf");
        List<T> values = new ArrayList<T>(items.size());
        double[] weights = new double[items.size()];
        int index = 0;
        for (T item : items) {
            values.add(Objects.requireNonNull(item, "item"));
            weights[index++] = requireWeight(weightOf.applyAsDouble(item), item);
        }
        return new WeightedPool<T>(values, weights);
    }

    public static <T> Builder<T> builder() {
        return new Builder<T>();
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public double totalWeight() {
        return total;
    }

    public List<T> entries() {
        return values;
    }

    public double weight(int index) {
        return weights[index];
    }

    /** 条目被抽中的概率，范围 0 到 1。 */
    public double chance(int index) {
        return weights[index] / total;
    }

    public T pick() {
        return pick(ThreadLocalRandom.current());
    }

    public T pick(Random random) {
        Objects.requireNonNull(random, "random");
        requireNonEmpty();
        return values.get(indexOf(random.nextDouble() * total));
    }

    /** 不放回地抽取最多 {@code count} 个条目；可抽条目不足时返回全部正权重条目。 */
    public List<T> pickDistinct(int count) {
        return pickDistinct(count, ThreadLocalRandom.current());
    }

    public List<T> pickDistinct(int count, Random random) {
        Objects.requireNonNull(random, "random");
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative");
        }
        double[] remaining = Arrays.copyOf(weights, weights.length);
        double remainingTotal = total;
        List<T> picked = new ArrayList<T>(Math.min(count, values.size()));
        while (picked.size() < count && remainingTotal > 0.0D) {
            double roll = random.nextDouble() * remainingTotal;
            int chosen = -1;
            int lastPositive = -1;
            for (int index = 0; index < remaining.length; index++) {
                if (remaining[index] <= 0.0D) {
                    continue;
                }
                lastPositive = index;
                roll -= remaining[index];
                if (roll < 0.0D) {
                    chosen = index;
                    break;
                }
            }
            if (chosen < 0) {
                chosen = lastPositive;
            }
            picked.add(values.get(chosen));
            remainingTotal -= remaining[chosen];
            remaining[chosen] = 0.0D;
            if (remainingTotal < 1.0E-12D * total) {
                remainingTotal = sumPositive(remaining);
            }
        }
        return Collections.unmodifiableList(picked);
    }

    private int indexOf(double roll) {
        int low = 0;
        int high = cumulative.length - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (roll < cumulative[middle]) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        // 浮点累加误差可能让 roll 落在最后一个零权重条目上，回退到最近的正权重条目。
        while (weights[low] <= 0.0D && low > 0) {
            low--;
        }
        return low;
    }

    private void requireNonEmpty() {
        if (values.isEmpty()) {
            throw new IllegalStateException("WeightedPool is empty");
        }
    }

    private static double sumPositive(double[] weights) {
        double sum = 0.0D;
        for (double weight : weights) {
            if (weight > 0.0D) {
                sum += weight;
            }
        }
        return sum;
    }

    private static double requireWeight(double weight, Object item) {
        if (Double.isNaN(weight) || Double.isInfinite(weight) || weight < 0.0D) {
            throw new IllegalArgumentException("Invalid weight " + weight + " for " + item);
        }
        return weight;
    }

    /** 逐项添加条目的构建器。 */
    public static final class Builder<T> {
        private final List<T> values = new ArrayList<T>();
        private final List<Double> weights = new ArrayList<Double>();

        private Builder() {
        }

        public Builder<T> add(T value, double weight) {
            values.add(Objects.requireNonNull(value, "value"));
            weights.add(Double.valueOf(requireWeight(weight, value)));
            return this;
        }

        public WeightedPool<T> build() {
            double[] array = new double[weights.size()];
            for (int index = 0; index < array.length; index++) {
                array[index] = weights.get(index).doubleValue();
            }
            return new WeightedPool<T>(new ArrayList<T>(values), array);
        }
    }
}
