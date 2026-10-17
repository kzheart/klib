package me.kzheart.klib.command;

import java.util.List;
import org.bukkit.entity.Player;

/** Java 方法声明保留 PlayerResolver 的平台返回类型。 */
public final class BukkitPlayerResolver implements PlayerResolver {
    public static final BukkitPlayerResolver INSTANCE = new BukkitPlayerResolver();
    private BukkitPlayerResolver() { }

    @Override public Player findExact(String name) { return BukkitPlayerResolverSupport.findExact(name); }
    @Override public List<String> suggest(String prefix) { return BukkitPlayerResolverSupport.suggest(prefix); }
}
