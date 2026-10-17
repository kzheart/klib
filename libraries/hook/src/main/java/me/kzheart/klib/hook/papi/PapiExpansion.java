package me.kzheart.klib.hook.papi;

import java.time.Duration;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.bukkit.OfflinePlayer;

/** 已编译占位符解析器；Java 入口保留未命中时的平台类型 null 返回。 */
public final class PapiExpansion {
    private final PapiExpansionState state;
    PapiExpansion(Map<String, Resolver> exact, Map<String, Resolver> prefixed) {
        state = new PapiExpansionState(exact, prefixed);
    }

    public String resolve(OfflinePlayer player, String parameters) { return state.resolve(player, parameters); }

    interface Resolver { String resolve(OfflinePlayer player, String suffix); }
    static Resolver exact(Function<? super OfflinePlayer, ?> function) { return PapiExpansionState.exact(function); }
    static Resolver prefixed(BiFunction<? super OfflinePlayer, String, ?> function) { return PapiExpansionState.prefixed(function); }
    static Resolver cached(Resolver delegate, Duration ttl) { return PapiExpansionState.cached(delegate, ttl); }
}
