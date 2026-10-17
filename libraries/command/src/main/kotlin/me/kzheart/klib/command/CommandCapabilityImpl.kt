package me.kzheart.klib.command

import java.util.function.Consumer
import java.util.function.Supplier
import me.kzheart.klib.KLogger
import me.kzheart.klib.command.api.CommandCapability
import me.kzheart.klib.command.api.CommandRegistration
import me.kzheart.klib.command.api.CommandSpec
import me.kzheart.klib.command.api.CommandErrorHandler
import me.kzheart.klib.command.api.CommandHelpStyle
import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope

class CommandCapabilityImpl @JvmOverloads constructor(private val bridge: CommandBridge, private val players: PlayerResolver,
    private val output: RichTextSink, private val messages: CommandMessages = DefaultCommandMessages.INSTANCE,
    private val logger: KLogger? = null, private val errorHandler: CommandErrorHandler? = null) : CommandCapability {
    constructor(bridge: CommandBridge) : this(bridge, BukkitPlayerResolver.INSTANCE, SpigotRichTextSink.INSTANCE)
    override fun registerAnnotated(owner: Scope, vararg handlers: Any): Disposable = AnnotatedCommands.register(this, owner, *handlers)
    override fun register(owner: Scope, name: String, configure: Consumer<in CommandSpec>): CommandRegistration {
        val spec = CommandSpecImpl.command(name)
        configure.accept(spec)
        return registerCompiled(owner, spec)
    }
    fun registerCompiled(owner: Scope, spec: CommandSpecImpl): CommandRegistration {
        if (spec.root().helpStyle == null) spec.root().helpStyle = Supplier {
            owner.findCapability(CommandHelpStyle::class.java).orElse(CommandHelpStyle.preset(CommandHelpStyle.Preset.ELEGANT))
        }
        val dispatcher = CommandDispatcher(spec, players, output, messages, logger, errorHandler)
        val registration = CommandRegistrationImpl(spec.name(), bridge.register(spec.name(), spec, dispatcher))
        return try { owner.install(registration) } catch (failure: RuntimeException) { registration.dispose(); throw failure }
    }
}
