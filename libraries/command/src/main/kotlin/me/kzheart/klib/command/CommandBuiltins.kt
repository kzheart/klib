package me.kzheart.klib.command

import java.util.concurrent.CompletionStage
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.function.Supplier
import java.util.logging.Logger
import me.kzheart.klib.command.api.CommandContext
import me.kzheart.klib.command.api.CommandSpec

class CommandBuiltins private constructor() {
    private var helpEnabled = true
    private var helpPermission: String? = null
    private var reloadAction: Runnable? = null
    private var reloadAsyncAction: Supplier<out CompletionStage<*>?>? = null
    private var reloadPermission: String? = null
    private var debugState: BooleanSupplier? = null
    private var debugAction: Consumer<Boolean>? = null
    private var debugPermission: String? = null
    fun help(enabled: Boolean, permission: String?): CommandBuiltins = apply {
        helpEnabled = enabled; helpPermission = normalizePermission(permission, null)
    }
    fun reload(permission: String?, action: Runnable): CommandBuiltins = apply {
        reloadPermission = normalizePermission(permission, "reload"); reloadAction = action; reloadAsyncAction = null
    }
    fun reloadAsync(permission: String?, action: Supplier<out CompletionStage<*>?>): CommandBuiltins = apply {
        reloadPermission = normalizePermission(permission, "reload"); reloadAsyncAction = action; reloadAction = null
    }
    fun debug(permission: String?, state: BooleanSupplier, action: Consumer<Boolean>): CommandBuiltins = apply {
        debugPermission = normalizePermission(permission, "debug"); debugState = state; debugAction = action
    }
    fun install(spec: CommandSpec) {
        if (helpEnabled) {
            val page = Arguments.optional(Arguments.integer("page", 1, Int.MAX_VALUE), 1)
            spec.literal("help") { child ->
                configure(child, CommandMessageKeys.BUILTIN_HELP_DESCRIPTION, helpPermission)
                child.argument(page) { pageNode -> pageNode.executes(object : DispatcherAwareCommandHandler {
                    override fun execute(context: CommandContext, dispatcher: CommandDispatcher) {
                        dispatcher.sendHelp(context.sender(), context.get(page), dispatcher.defaultHelpPageSize())
                    }
                }) }
            }
        }
        if (reloadAction != null || reloadAsyncAction != null) spec.literal("reload") { child ->
            configure(child, CommandMessageKeys.BUILTIN_RELOAD_DESCRIPTION, reloadPermission)
            child.executes(ReloadHandler(reloadAction, reloadAsyncAction))
        }
        if (debugState != null) spec.literal("debug") { child ->
            configure(child, CommandMessageKeys.BUILTIN_DEBUG_DESCRIPTION, debugPermission)
            val state = requireNotNull(debugState)
            val action = requireNotNull(debugAction)
            child.executes(object : DispatcherAwareCommandHandler {
                override fun execute(context: CommandContext, dispatcher: CommandDispatcher) {
                    val enabled = !state.asBoolean
                    action.accept(enabled)
                    dispatcher.sendMessage(context.sender(), if (enabled) CommandMessageKeys.BUILTIN_DEBUG_ENABLED else CommandMessageKeys.BUILTIN_DEBUG_DISABLED)
                }
            })
        }
    }
    private class ReloadHandler(private val action: Runnable?, private val asyncAction: Supplier<out CompletionStage<*>?>?) : DispatcherAwareCommandHandler {
        override fun execute(context: CommandContext, dispatcher: CommandDispatcher) {
            if (asyncAction == null) {
                try { requireNotNull(action).run() }
                catch (failure: RuntimeException) { throw CommandFailure(CommandMessageKeys.BUILTIN_RELOAD_FAILURE, failure) }
                dispatcher.sendMessage(context.sender(), CommandMessageKeys.BUILTIN_RELOAD_SUCCESS)
                return
            }
            val completion = asyncAction.get() ?: throw IllegalStateException("reload action returned null completion stage")
            completion.whenComplete { _, failure ->
                if (failure == null) dispatcher.sendMessage(context.sender(), CommandMessageKeys.BUILTIN_RELOAD_SUCCESS)
                else dispatcher.sendFailure(context, failure, CommandMessageKeys.BUILTIN_RELOAD_FAILURE)
            }
        }
    }
    companion object {
        const val PERMISSION_NONE = "<none>"
        private const val DEFAULT_ADMIN_PERMISSION = "klib.command.builtin.admin"
        private val logger = Logger.getLogger(CommandBuiltins::class.java.name)
        @JvmStatic fun create(): CommandBuiltins = CommandBuiltins()
        @JvmStatic fun standard(reloadAction: Runnable, debugState: BooleanSupplier, debugAction: Consumer<Boolean>): CommandBuiltins {
            logger.warning("CommandBuiltins.standard(...) 未指定权限，reload/debug 默认要求 $DEFAULT_ADMIN_PERMISSION；请改用带权限参数的重载")
            return standard(DEFAULT_ADMIN_PERMISSION, reloadAction, debugState, debugAction)
        }
        @JvmStatic fun standard(permission: String, reloadAction: Runnable, debugState: BooleanSupplier, debugAction: Consumer<Boolean>): CommandBuiltins =
            create().reload(permission, reloadAction).debug(permission, debugState, debugAction)
        @JvmStatic fun standardAsync(permission: String, reloadAction: Supplier<out CompletionStage<*>?>, debugState: BooleanSupplier,
            debugAction: Consumer<Boolean>): CommandBuiltins = create().reloadAsync(permission, reloadAction).debug(permission, debugState, debugAction)
        private fun configure(spec: CommandSpec, description: String, permission: String?) {
            val typed = spec as? CommandSpecImpl ?: throw IllegalArgumentException("内建命令需要由 CommandModule / Scope.command / CommandSpecImpl.command 创建的命令规格")
            typed.descriptionKey(description)
            if (permission != null) spec.permission(permission)
        }
        private fun normalizePermission(permission: String?, sensitiveContext: String?): String? {
            if (permission == PERMISSION_NONE) return null
            if (permission.isNullOrBlank()) {
                if (sensitiveContext != null) logger.warning("内置命令 $sensitiveContext 未设置权限，任何玩家均可执行；如确认无需权限请传 CommandBuiltins.PERMISSION_NONE")
                return null
            }
            return permission.trim()
        }
    }
}
