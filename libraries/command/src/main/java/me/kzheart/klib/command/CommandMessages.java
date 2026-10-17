package me.kzheart.klib.command;

import me.kzheart.klib.lang.RichText;
import org.bukkit.command.CommandSender;

import java.util.Map;

public interface CommandMessages {
    RichText resolve(CommandSender sender, String key, Map<String, ?> placeholders);
}
