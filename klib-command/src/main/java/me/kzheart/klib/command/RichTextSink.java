package me.kzheart.klib.command;

import me.kzheart.klib.lang.RichText;
import org.bukkit.command.CommandSender;

@FunctionalInterface
public interface RichTextSink {
    void send(CommandSender sender, RichText text);
}
