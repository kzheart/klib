package me.kzheart.klib.hook.papi;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.bukkit.OfflinePlayer;

/** 精确匹配、前缀匹配及可选缓存占位符的构建器。 */
public final class PapiDsl {

    private final Map<String, PapiExpansion.Resolver> exact =
            new LinkedHashMap<String, PapiExpansion.Resolver>();
    private final Map<String, PapiExpansion.Resolver> prefixed =
            new LinkedHashMap<String, PapiExpansion.Resolver>();

    public PapiDsl key(String key, Function<? super OfflinePlayer, ?> resolver) {
        put(exact, key, PapiExpansion.exact(resolver));
        return this;
    }

    public PapiDsl keyCached(
            String key,
            Duration ttl,
            Function<? super OfflinePlayer, ?> resolver
    ) {
        put(exact, key, PapiExpansion.cached(PapiExpansion.exact(resolver), ttl));
        return this;
    }

    public PapiDsl prefixed(
            String prefix,
            BiFunction<? super OfflinePlayer, String, ?> resolver
    ) {
        put(prefixed, prefix, PapiExpansion.prefixed(resolver));
        return this;
    }

    public PapiDsl prefixedCached(
            String prefix,
            Duration ttl,
            BiFunction<? super OfflinePlayer, String, ?> resolver
    ) {
        put(prefixed, prefix, PapiExpansion.cached(PapiExpansion.prefixed(resolver), ttl));
        return this;
    }

    PapiExpansion build() {
        return new PapiExpansion(exact, prefixed);
    }

    private static void put(
            Map<String, PapiExpansion.Resolver> destination,
            String key,
            PapiExpansion.Resolver resolver
    ) {
        Objects.requireNonNull(key, "key");
        String normalized = key.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("placeholder key must not be blank");
        }
        if (destination.put(normalized, resolver) != null) {
            throw new IllegalArgumentException("duplicate placeholder key: " + normalized);
        }
    }
}
