package me.kzheart.klib.command;

import me.kzheart.klib.lang.RichText;
import org.bukkit.command.CommandSender;

public final class PlainRichTextSink implements RichTextSink {
    public static final PlainRichTextSink INSTANCE = new PlainRichTextSink();

    private PlainRichTextSink() {
    }

    @Override
    public void send(CommandSender sender, RichText text) {
        sender.sendMessage(text.plainText());
    }
}
