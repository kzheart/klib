package me.kzheart.klib.hook.papi;

import java.text.DecimalFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.bukkit.OfflinePlayer;

/** 由精确键和前缀键组成的已编译占位符解析器。 */
public final class PapiExpansion {

    private final Map<String, Resolver> exact;
    private final Map<String, Resolver> prefixed;

    PapiExpansion(Map<String, Resolver> exact, Map<String, Resolver> prefixed) {
        this.exact = new LinkedHashMap<String, Resolver>(exact);
        // 最长前缀优先匹配，避免注册顺序影响结果。
        List<Map.Entry<String, Resolver>> ordered =
                new ArrayList<Map.Entry<String, Resolver>>(prefixed.entrySet());
        Collections.sort(ordered, (left, right) ->
                Integer.compare(right.getKey().length(), left.getKey().length()));
        this.prefixed = new LinkedHashMap<String, Resolver>();
        for (Map.Entry<String, Resolver> entry : ordered) {
            this.prefixed.put(entry.getKey(), entry.getValue());
        }
    }

    public String resolve(OfflinePlayer player, String parameters) {
        Objects.requireNonNull(parameters, "parameters");
        Resolver direct = exact.get(parameters);
        if (direct != null) {
            return direct.resolve(player, "");
        }
        for (Map.Entry<String, Resolver> entry : prefixed.entrySet()) {
            if (parameters.startsWith(entry.getKey())) {
                return entry.getValue().resolve(player, parameters.substring(entry.getKey().length()));
            }
        }
        return null;
    }

    interface Resolver {
        String resolve(OfflinePlayer player, String suffix);
    }

    static Resolver exact(Function<? super OfflinePlayer, ?> function) {
        Objects.requireNonNull(function, "function");
        return (player, suffix) -> stringify(function.apply(player));
    }

    static Resolver prefixed(BiFunction<? super OfflinePlayer, String, ?> function) {
        Objects.requireNonNull(function, "function");
        return (player, suffix) -> stringify(function.apply(player, suffix));
    }

    static Resolver cached(Resolver delegate, Duration ttl) {
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        return new CachingResolver(delegate, ttl.toNanos());
    }

    private static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number) {
            return new DecimalFormat("0.##").format(value);
        }
        return String.valueOf(value);
    }

    private static final class CachingResolver implements Resolver {
        private static final int MAX_ENTRIES = 256;

        private final Resolver delegate;
        private final long ttlNanos;
        private final Map<Key, Value> cache = new LinkedHashMap<Key, Value>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Value> eldest) {
                return size() > MAX_ENTRIES;
            }
        };

        private CachingResolver(Resolver delegate, long ttlNanos) {
            this.delegate = delegate;
            this.ttlNanos = ttlNanos;
        }

        @Override
        public synchronized String resolve(OfflinePlayer player, String suffix) {
            long now = System.nanoTime();
            Key key = new Key(player == null ? null : player.getUniqueId(), suffix);
            Value existing = cache.get(key);
            if (existing != null && now - existing.createdAt < ttlNanos) {
                return existing.content;
            }
            String content = delegate.resolve(player, suffix);
            cache.put(key, new Value(content, now));
            return content;
        }
    }

    private static final class Key {
        private final UUID playerId;
        private final String suffix;

        private Key(UUID playerId, String suffix) {
            this.playerId = playerId;
            this.suffix = suffix;
        }

        @Override
        public boolean equals(Object candidate) {
            if (this == candidate) {
                return true;
            }
            if (!(candidate instanceof Key)) {
                return false;
            }
            Key other = (Key) candidate;
            return Objects.equals(playerId, other.playerId) && suffix.equals(other.suffix);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(playerId) + suffix.hashCode();
        }
    }

    private static final class Value {
        private final String content;
        private final long createdAt;

        private Value(String content, long createdAt) {
            this.content = content;
            this.createdAt = createdAt;
        }
    }
}
