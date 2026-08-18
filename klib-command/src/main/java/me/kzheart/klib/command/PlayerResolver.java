package me.kzheart.klib.command;

import org.bukkit.entity.Player;

import java.util.List;

public interface PlayerResolver {
    Player findExact(String name);

    List<String> suggest(String prefix);
}
