package me.kzheart.klib.command;

import me.kzheart.klib.lang.MessageCatalog;
import me.kzheart.klib.lang.MessagePipeline;
import me.kzheart.klib.lang.MessageRecipient;
import me.kzheart.klib.lang.PlaceholderApi;
import me.kzheart.klib.lang.RichText;
import org.bukkit.command.CommandSender;

import java.util.Map;
import java.util.Objects;

public final class MessagePipelineCommandMessages implements CommandMessages {
    private final MessagePipeline pipeline;

    public MessagePipelineCommandMessages(MessagePipeline pipeline) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    }

    public MessagePipelineCommandMessages(
            MessageCatalog catalog,
            String prefix,
            PlaceholderApi placeholderApi
    ) {
        pipeline = new MessagePipeline(catalog, prefix, placeholderApi, (recipient, message) -> {
        });
    }

    @Override
    public RichText resolve(CommandSender sender, String key, Map<String, ?> placeholders) {
        return pipeline.render(MessageRecipient.commandSender(sender), key, placeholders);
    }
}
