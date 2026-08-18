package me.kzheart.klib.command;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class BukkitPlayerResolver implements PlayerResolver {
    public static final BukkitPlayerResolver INSTANCE = new BukkitPlayerResolver();

    private BukkitPlayerResolver() {
    }

    @Override
    public Player findExact(String name) {
        return Bukkit.getPlayerExact(name);
    }

    @Override
    public List<String> suggest(String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<String>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(normalized)) {
                names.add(player.getName());
            }
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }
}
