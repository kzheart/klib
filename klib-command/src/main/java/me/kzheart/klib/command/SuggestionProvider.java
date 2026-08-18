package me.kzheart.klib.command;

import org.bukkit.command.CommandSender;

import java.util.List;

@FunctionalInterface
public interface SuggestionProvider {
    List<String> suggest(CommandSender sender, String prefix);
}
