package me.kzheart.klib.command;

import me.kzheart.klib.KLogger;
import me.kzheart.klib.command.api.CommandCapability;
import me.kzheart.klib.command.api.CommandRegistration;
import me.kzheart.klib.command.api.CommandSpec;
import me.kzheart.klib.command.api.CommandErrorHandler;
import me.kzheart.klib.command.api.CommandHelpStyle;
import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.Scope;

import java.util.function.Consumer;

public final class CommandCapabilityImpl implements CommandCapability {
    private final CommandBridge bridge;
    private final PlayerResolver players;
    private final RichTextSink output;
    private final CommandMessages messages;
    private final KLogger logger;
    private final CommandErrorHandler errorHandler;

    public CommandCapabilityImpl(CommandBridge bridge) {
        this(
                bridge,
                BukkitPlayerResolver.INSTANCE,
                SpigotRichTextSink.INSTANCE,
                DefaultCommandMessages.INSTANCE,
                null);
    }

    public CommandCapabilityImpl(
            CommandBridge bridge,
            PlayerResolver players,
            RichTextSink output
    ) {
        this(bridge, players, output, DefaultCommandMessages.INSTANCE, null);
    }

    public CommandCapabilityImpl(
            CommandBridge bridge,
            PlayerResolver players,
            RichTextSink output,
            CommandMessages messages
    ) {
        this(bridge, players, output, messages, null);
    }

    public CommandCapabilityImpl(
            CommandBridge bridge,
            PlayerResolver players,
            RichTextSink output,
            CommandMessages messages,
            KLogger logger
    ) {
        this(bridge, players, output, messages, logger, null);
    }

    public CommandCapabilityImpl(CommandBridge bridge, PlayerResolver players, RichTextSink output,
                                  CommandMessages messages, KLogger logger, CommandErrorHandler errorHandler) {
        if (bridge == null) {
            throw new NullPointerException("bridge");
        }
        if (players == null) {
            throw new NullPointerException("players");
        }
        if (output == null) {
            throw new NullPointerException("output");
        }
        if (messages == null) {
            throw new NullPointerException("messages");
        }
        this.bridge = bridge;
        this.players = players;
        this.output = output;
        this.messages = messages;
        this.logger = logger;
        this.errorHandler = errorHandler;
    }

    @Override
    public Disposable registerAnnotated(Scope owner, Object... handlers) {
        return AnnotatedCommands.register(this, owner, handlers);
    }

    @Override
    public CommandRegistration register(
            Scope owner,
            String name,
            Consumer<? super CommandSpec> configure
    ) {
        if (owner == null) {
            throw new NullPointerException("owner");
        }
        if (configure == null) {
            throw new NullPointerException("configure");
        }
        CommandSpecImpl spec = CommandSpecImpl.command(name);
        configure.accept(spec);
        return registerCompiled(owner, spec);
    }

    CommandRegistration registerCompiled(Scope owner, CommandSpecImpl spec) {
        if (spec.root().helpStyle == null) spec.root().helpStyle = () -> owner.findCapability(CommandHelpStyle.class)
                .orElse(CommandHelpStyle.preset(CommandHelpStyle.Preset.ELEGANT));
        CommandDispatcher dispatcher = new CommandDispatcher(spec, players, output, messages, logger, errorHandler);
        Disposable bridgeRegistration = bridge.register(spec.name(), spec, dispatcher);
        CommandRegistrationImpl registration = new CommandRegistrationImpl(
                spec.name(),
                bridgeRegistration);
        try {
            return owner.install(registration);
        } catch (RuntimeException failure) {
            registration.dispose();
            throw failure;
        }
    }
}
