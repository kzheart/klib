package me.kzheart.klib.lang;

import me.kzheart.klib.config.api.ConfigDocument;
import me.kzheart.klib.scope.Scope;
import org.bukkit.command.CommandSender;

import java.util.Map;

public final class LangRuntime {
    private final Scope owner;
    private final String locale;
    private final ConfigDocument<LanguageFile> document;
    private final ReloadableMessageCatalog catalog;
    private final MessagePipeline pipeline;

    LangRuntime(
            Scope owner,
            String locale,
            ConfigDocument<LanguageFile> document,
            ReloadableMessageCatalog catalog,
            MessagePipeline pipeline
    ) {
        this.owner = owner;
        this.locale = locale;
        this.document = document;
        this.catalog = catalog;
        this.pipeline = pipeline;
    }

    public String locale() {
        return locale;
    }

    public MessageCatalog catalog() {
        ensureOpen();
        return catalog;
    }

    public MessagePipeline pipeline() {
        ensureOpen();
        return pipeline;
    }

    public RichText send(MessageRecipient recipient, String key) {
        ensureOpen();
        return pipeline.send(recipient, key);
    }

    public RichText send(MessageRecipient recipient, String key, Map<String, ?> placeholders) {
        ensureOpen();
        return pipeline.send(recipient, key, placeholders);
    }

    public RichText send(
            MessageRecipient recipient,
            MessageRoute route,
            String key,
            Map<String, ?> placeholders
    ) {
        ensureOpen();
        return pipeline.send(recipient, route, key, placeholders);
    }

    public RichText send(CommandSender sender, String key) {
        return send(MessageRecipient.commandSender(sender), key);
    }

    public RichText send(CommandSender sender, String key, Map<String, ?> placeholders) {
        return send(MessageRecipient.commandSender(sender), key, placeholders);
    }

    public RichText send(
            CommandSender sender,
            MessageRoute route,
            String key,
            Map<String, ?> placeholders
    ) {
        return send(MessageRecipient.commandSender(sender), route, key, placeholders);
    }

    public ConfigDocument<?> configDocument() {
        return document;
    }

    private void ensureOpen() {
        if (owner.isClosed()) {
            throw new IllegalStateException("Language runtime is closed: " + locale);
        }
    }

    static final class LanguageFile {
        public Map<String, Object> messages;

        LanguageFile() {
        }
    }
}
