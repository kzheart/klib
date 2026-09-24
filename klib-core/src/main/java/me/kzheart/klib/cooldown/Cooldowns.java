package me.kzheart.klib.cooldown;

import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.Scope;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * 按任意键记录冷却截止时间的线程安全表。
 *
 * <p>安装进 {@link Scope} 后随作用域关闭清空；过期条目在读取时移除，并在写入时定期批量清理。
 */
public final class Cooldowns<K> implements Disposable {
    private static final int PURGE_INTERVAL = 256;

    private final Clock clock;
    private final ConcurrentHashMap<K, Long> deadlines = new ConcurrentHashMap<K, Long>();
    private final AtomicInteger writes = new AtomicInteger();

    private Cooldowns(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static <K> Cooldowns<K> create() {
        return new Cooldowns<K>(Clock.systemUTC());
    }

    public static <K> Cooldowns<K> create(Clock clock) {
        return new Cooldowns<K>(clock);
    }

    /** 以玩家 UUID 为键，安装进作用域，并在玩家退出时移除其冷却。 */
    public static Cooldowns<UUID> perPlayer(Scope scope) {
        return perPlayer(scope, Clock.systemUTC());
    }

    public static Cooldowns<UUID> perPlayer(Scope scope, Clock clock) {
        Objects.requireNonNull(scope, "scope");
        Cooldowns<UUID> cooldowns = scope.install(new Cooldowns<UUID>(clock));
        scope.on(PlayerQuitEvent.class, event -> cooldowns.reset(event.getPlayer().getUniqueId()));
        return cooldowns;
    }

    /** 冷却结束时开始新的冷却并返回成功；仍在冷却中时不修改并返回剩余时间。 */
    public Attempt tryAcquire(K key, Duration duration) {
        Objects.requireNonNull(key, "key");
        long length = requireNonNegative(duration);
        long now = clock.millis();
        AtomicBoolean acquired = new AtomicBoolean();
        Long deadline = deadlines.compute(key, (ignored, current) -> {
            if (current != null && current.longValue() > now) {
                return current;
            }
            acquired.set(true);
            return length == 0L ? null : Long.valueOf(saturatedAdd(now, length));
        });
        afterWrite();
        if (acquired.get()) {
            return new Attempt(true, Duration.ZERO);
        }
        return new Attempt(false, Duration.ofMillis(deadline.longValue() - now));
    }

    public boolean isReady(K key) {
        return remainingMillis(key) == 0L;
    }

    public Duration remaining(K key) {
        return Duration.ofMillis(remainingMillis(key));
    }

    /** 无条件把冷却设为从现在起的时长；时长为零等价于 {@link #reset}。 */
    public void set(K key, Duration duration) {
        Objects.requireNonNull(key, "key");
        long length = requireNonNegative(duration);
        if (length == 0L) {
            deadlines.remove(key);
        } else {
            deadlines.put(key, Long.valueOf(saturatedAdd(clock.millis(), length)));
        }
        afterWrite();
    }

    /** 缩短剩余冷却；缩短到零时视为结束。 */
    public void reduce(K key, Duration amount) {
        Objects.requireNonNull(key, "key");
        long length = requireNonNegative(amount);
        long now = clock.millis();
        deadlines.computeIfPresent(key, (ignored, current) -> {
            long next = current.longValue() - length;
            return next <= now ? null : Long.valueOf(next);
        });
    }

    /** 延长剩余冷却；未在冷却中时从现在开始计算。 */
    public void extend(K key, Duration amount) {
        Objects.requireNonNull(key, "key");
        long length = requireNonNegative(amount);
        long now = clock.millis();
        deadlines.compute(key, (ignored, current) -> {
            long base = current == null || current.longValue() <= now ? now : current.longValue();
            long next = saturatedAdd(base, length);
            return next <= now ? null : Long.valueOf(next);
        });
        afterWrite();
    }

    public void reset(K key) {
        deadlines.remove(Objects.requireNonNull(key, "key"));
    }

    public void clearIf(Predicate<? super K> filter) {
        Objects.requireNonNull(filter, "filter");
        deadlines.keySet().removeIf(filter);
    }

    public void clear() {
        deadlines.clear();
    }

    /** 当前仍在冷却中的条目数。 */
    public int size() {
        purgeExpired();
        return deadlines.size();
    }

    public void purgeExpired() {
        long now = clock.millis();
        Iterator<Map.Entry<K, Long>> iterator = deadlines.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().longValue() <= now) {
                iterator.remove();
            }
        }
    }

    @Override
    public void dispose() {
        deadlines.clear();
    }

    private long remainingMillis(K key) {
        Objects.requireNonNull(key, "key");
        Long deadline = deadlines.get(key);
        if (deadline == null) {
            return 0L;
        }
        long remaining = deadline.longValue() - clock.millis();
        if (remaining <= 0L) {
            deadlines.remove(key, deadline);
            return 0L;
        }
        return remaining;
    }

    private void afterWrite() {
        if (writes.incrementAndGet() % PURGE_INTERVAL == 0) {
            purgeExpired();
        }
    }

    private static long requireNonNegative(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Cooldown duration must not be negative: " + duration);
        }
        try {
            return duration.toMillis();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatedAdd(long left, long right) {
        long sum = left + right;
        return ((left ^ sum) & (right ^ sum)) < 0 ? Long.MAX_VALUE : sum;
    }

    /** 一次 {@link #tryAcquire} 的结果。 */
    public static final class Attempt {
        private final boolean acquired;
        private final Duration remaining;

        private Attempt(boolean acquired, Duration remaining) {
            this.acquired = acquired;
            this.remaining = remaining;
        }

        public boolean acquired() {
            return acquired;
        }

        /** 未获取时距冷却结束的时间；获取成功时为零。 */
        public Duration remaining() {
            return remaining;
        }
    }
}
