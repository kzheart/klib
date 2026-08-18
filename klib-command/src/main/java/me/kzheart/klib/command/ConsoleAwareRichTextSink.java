package me.kzheart.klib.command;

import me.kzheart.klib.lang.RichText;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ConsoleAwareRichTextSink implements RichTextSink {
    private final RichTextSink playerSink;

    public ConsoleAwareRichTextSink(RichTextSink playerSink) {
        if (playerSink == null) {
            throw new NullPointerException("playerSink");
        }
        this.playerSink = playerSink;
    }

    @Override
    public void send(CommandSender sender, RichText text) {
        if (sender instanceof Player) {
            playerSink.send(sender, text);
        } else {
            PlainRichTextSink.INSTANCE.send(sender, text);
        }
    }
}
