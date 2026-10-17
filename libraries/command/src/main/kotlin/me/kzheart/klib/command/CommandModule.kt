package me.kzheart.klib.command

import java.util.Locale
import me.kzheart.klib.KLogger
import me.kzheart.klib.KPlugin
import me.kzheart.klib.command.api.CommandCapability
import me.kzheart.klib.command.api.CommandErrorHandler
import me.kzheart.klib.lang.MessagePipeline
import me.kzheart.klib.scope.Scope

/** Startup command declaration entry point; scope disposal immediately disables registered bindings. */
class CommandModule private constructor() {
    companion object {
        @JvmStatic fun install(plugin: KPlugin): CommandCapability = install(plugin.context().scope(),
            BukkitCommandRegistrar.discover(plugin, plugin.name.lowercase(Locale.ROOT)))
        @JvmStatic fun install(plugin: KPlugin, errors: CommandErrorHandler): CommandCapability = install(plugin.context().scope(),
            BukkitCommandRegistrar.discover(plugin, plugin.name.lowercase(Locale.ROOT)), errors)
        @JvmStatic fun install(scope: Scope, bridge: CommandBridge): CommandCapability =
            install(scope, bridge, BukkitPlayerResolver.INSTANCE, SpigotRichTextSink.INSTANCE, DefaultCommandMessages.INSTANCE)
        @JvmStatic fun install(scope: Scope, bridge: CommandBridge, errors: CommandErrorHandler): CommandCapability =
            install(scope, bridge, BukkitPlayerResolver.INSTANCE, SpigotRichTextSink.INSTANCE, DefaultCommandMessages.INSTANCE, errors)
        @JvmStatic fun install(scope: Scope, bridge: CommandBridge, messages: MessagePipeline): CommandCapability =
            install(scope, bridge, BukkitPlayerResolver.INSTANCE, SpigotRichTextSink.INSTANCE, MessagePipelineCommandMessages(messages))
        @JvmStatic fun install(scope: Scope, bridge: CommandBridge, messages: MessagePipeline, errors: CommandErrorHandler): CommandCapability =
            install(scope, bridge, BukkitPlayerResolver.INSTANCE, SpigotRichTextSink.INSTANCE, MessagePipelineCommandMessages(messages), errors)
        @JvmStatic fun install(scope: Scope, bridge: CommandBridge, players: PlayerResolver, output: RichTextSink,
            messages: CommandMessages): CommandCapability = installWithErrors(scope, bridge, players, output, messages, null)
        @JvmStatic fun install(scope: Scope, bridge: CommandBridge, players: PlayerResolver, output: RichTextSink,
            messages: CommandMessages, errors: CommandErrorHandler): CommandCapability = installWithErrors(scope, bridge, players, output, messages, errors)
        private fun installWithErrors(scope: Scope, bridge: CommandBridge, players: PlayerResolver, output: RichTextSink,
            messages: CommandMessages, errors: CommandErrorHandler?): CommandCapability = scope.registerCapability(CommandCapability::class.java,
                CommandCapabilityImpl(bridge, players, output, messages, scope.findCapability(KLogger::class.java).orElse(null), errors))
    }
}
