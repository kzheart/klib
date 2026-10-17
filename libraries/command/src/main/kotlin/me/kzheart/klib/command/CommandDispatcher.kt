package me.kzheart.klib.command

import java.util.Locale
import java.util.TreeSet
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger
import me.kzheart.klib.KLogger
import me.kzheart.klib.command.api.CommandArgument
import me.kzheart.klib.command.api.CommandContext
import me.kzheart.klib.command.api.CommandErrorHandler
import me.kzheart.klib.command.api.CommandSpec
import me.kzheart.klib.diagnostic.DiagnosticSource
import me.kzheart.klib.lang.MessageColor
import me.kzheart.klib.lang.RichText
import me.kzheart.klib.lang.RichTextSegment
import me.kzheart.klib.lang.TextAction
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** Routing, feedback and diagnostics share the same access and parsing boundary. */
class CommandDispatcher @JvmOverloads constructor(
    spec: CommandSpec,
    private val players: PlayerResolver,
    private val output: RichTextSink,
    private val messages: CommandMessages = DefaultCommandMessages.INSTANCE,
    private val logger: KLogger? = null,
    private val errorHandler: CommandErrorHandler? = null,
) : DiagnosticSource {
    private val spec = spec as? CommandSpecImpl
        ?: throw IllegalArgumentException("命令规格必须由 CommandModule / Scope.command / CommandSpecImpl.command 创建")
    private val helpRenderer = HelpRenderer(messages)
    private val invocations = AtomicLong()
    private val failures = AtomicLong()
    @Volatile private var lastFailureType = ""

    constructor(spec: CommandSpec) : this(spec, BukkitPlayerResolver.INSTANCE, SpigotRichTextSink.INSTANCE)

    fun execute(sender: CommandSender, rawArgs: Array<String>): CommandResult {
        requireInvocation(rawArgs)
        invocations.incrementAndGet()
        val args = stripEmptyTokens(rawArgs, false)
        var current = spec.root()
        restriction(sender, current)?.let { return emit(sender, it) }
        // Insertion order is needed for deepest-by-name lookup; argument identity remains unchanged.
        val values = linkedMapOf<CommandArgument<*>, Any?>()
        val consumed = arrayListOf<String>()
        var index = 0
        while (index < args.size) {
            val token = args[index]
            val literal = findLiteral(current, token)
            val literalRestriction = literal?.let { restriction(sender, it) }
            if (literal != null && literalRestriction == null) {
                current = literal
                consumed.add(requireNotNull(literal.literal))
                index++
                continue
            }
            // Inaccessible literals must not hide a sibling argument route.
            val parsed = parseArgument(current, sender, args, index, values)
            val parsedNode = parsed.node
            if (parsedNode != null) {
                val restricted = restriction(sender, parsedNode)
                if (restricted == null) {
                    values[requireNotNull(parsedNode.argument)] = parsed.value
                    current = parsedNode
                    consumed.addAll(args.slice(index until parsed.nextIndex))
                    index = parsed.nextIndex
                    continue
                }
                return emit(sender, if (literal == null) restricted else requireNotNull(literalRestriction))
            }
            if (literal != null) return emit(sender, requireNotNull(literalRestriction))
            val detail = parsed.error?.let { messages.resolve(sender, it.key(), it.placeholders()) }
                ?: messages.resolve(sender, CommandMessageKeys.UNKNOWN_ARGUMENT, MessagePlaceholders.of("argument", token))
            val status = if (parsed.error == null) CommandResult.Status.UNKNOWN_ARGUMENT else CommandResult.Status.INVALID_ARGUMENT
            return emit(sender, CommandResult.message(status, errorAt(sender, args, index, detail,
                suggestions(current, sender, token, values))))
        }
        while (current.handler == null) {
            val optional = optionalChild(current, sender) ?: break
            values[requireNotNull(optional.argument)] = optional.argument.defaultValue()
            current = optional
        }
        val handler = current.handler
        if (handler != null) {
            val context = CommandContextImpl(sender, spec.name(), values).apply { errorHandler = errorsFor(current) }
            try {
                if (handler is DispatcherAwareCommandHandler) handler.execute(context, this) else handler.execute(context)
            } catch (refusal: CommandRejectedException) {
                sender.sendMessage(refusal.message)
                return failed()
            } catch (failure: CommandFailure) {
                sendFailure(context, failure.cause ?: failure, failure.messageKey())
                return failed()
            } catch (failure: RuntimeException) {
                reportFailure(failure)
                handleError(context, failure)
                return failed()
            } catch (failure: Error) {
                reportFailure(failure)
                handleError(context, failure)
                throw failure
            }
            return CommandResult.success()
        }
        if (current.children.isNotEmpty()) {
            if (current != spec.root()) {
                val usage = helpRenderer.usage(current, sender, usagePrefix(consumed), DEFAULT_HELP_PAGE_SIZE)
                if (usage != null) return emit(sender, CommandResult.message(CommandResult.Status.INCOMPLETE, usage))
            }
            val help = helpRenderer.render(spec, sender, 1, defaultHelpPageSize())
            return emit(sender, CommandResult.message(CommandResult.Status.HELP, help.content()))
        }
        return emit(sender, CommandResult.message(CommandResult.Status.INCOMPLETE, message(sender, CommandMessageKeys.INCOMPLETE)))
    }

    private fun reportFailure(failure: Throwable) {
        failures.incrementAndGet()
        lastFailureType = failure.javaClass.name
        logFailure("命令处理失败", failure)
    }

    private fun logFailure(message: String, failure: Throwable) {
        val detail = "$message: /${spec.name()}"
        if (logger != null) logger.error(detail, failure) else fallbackLogger.log(Level.SEVERE, detail, failure)
    }

    override fun diagnosticName(): String = "command"
    override fun diagnosticSnapshot(): Map<String, *> = linkedMapOf(
        "name" to spec.name(), "invocations" to invocations.get(), "failures" to failures.get(), "last_failure" to lastFailureType)

    fun sendFailure(sender: CommandSender, failure: Throwable) = sendFailure(sender, failure, null)
    fun sendFailure(sender: CommandSender, failure: Throwable, reasonKey: String?) {
        val context = CommandContextImpl(sender, spec.name(), emptyMap()).apply { errorHandler = errorsFor(spec.root()) }
        sendFailure(context, failure, reasonKey)
    }
    fun sendFailure(context: CommandContext, failure: Throwable, reasonKey: String?) {
        reportFailure(failure)
        val reason = if (reasonKey == null) null else displayableReason(failure)
        if (selectedErrorHandler(context) == null && reason != null) {
            emit(context.sender(), CommandResult.message(CommandResult.Status.FAILED,
                messages.resolve(context.sender(), reasonKey, MessagePlaceholders.of("reason", reason))))
        } else handleError(context, failure)
    }

    private fun errorsFor(node: CommandNode): CommandErrorHandler? =
        generateSequence(node) { it.parent }.mapNotNull { it.errorHandler }.firstOrNull() ?: errorHandler
    private fun selectedErrorHandler(context: CommandContext): CommandErrorHandler? =
        if (context is CommandContextImpl) context.errorHandler else errorHandler
    private fun handleError(context: CommandContext, failure: Throwable) {
        val selected = selectedErrorHandler(context) ?: return
        try { selected.handle(context, failure) }
        catch (handlerFailure: RuntimeException) { logFailure("命令异常处理器失败", handlerFailure) }
    }

    private fun usagePrefix(consumed: List<String>): String = (listOf("/${spec.name()}") + consumed).joinToString(" ")

    fun complete(sender: CommandSender, rawArgs: Array<String>): List<String> {
        requireInvocation(rawArgs)
        val args = stripEmptyTokens(rawArgs, true)
        val values = linkedMapOf<CommandArgument<*>, Any?>()
        var current = spec.root()
        if (!isAccessible(sender, current)) return emptyList()
        if (args.isEmpty()) return suggestions(current, sender, "", values)
        var index = 0
        while (index < args.size - 1) {
            val literal = findLiteral(current, args[index])
            if (literal != null && isAccessible(sender, literal)) {
                current = literal
                index++
                continue
            }
            val parsed = parseArgument(current, sender, args, index, values)
            val node = parsed.node ?: return emptyList()
            if (!isAccessible(sender, node)) return emptyList()
            values[requireNotNull(node.argument)] = parsed.value
            current = node
            index = parsed.nextIndex
            if (index >= args.size) return emptyList()
        }
        return suggestions(current, sender, args.last(), values)
    }

    fun renderHelp(sender: CommandSender, page: Int, pageSize: Int): HelpPage = helpRenderer.render(spec, sender, page, pageSize)
    fun sendHelp(sender: CommandSender, page: Int, pageSize: Int): HelpPage = renderHelp(sender, page, pageSize).also {
        output.send(sender, it.content())
    }

    private fun parseArgument(current: CommandNode, sender: CommandSender, args: Array<String>, index: Int,
        values: Map<CommandArgument<*>, Any?>): ParsedArgument {
        var firstError: ArgumentException? = null
        for (child in current.children) {
            val argument = child.argument ?: continue
            if (!isAccessible(sender, child)) continue
            val input = if (argument.isGreedy) join(args, index) else args[index]
            try {
                val value = argument.parse(input, players, CommandContextImpl(sender, spec.name(), values))
                return ParsedArgument(child, value, if (argument.isGreedy) args.size else index + 1, null)
            } catch (failure: ArgumentException) {
                if (firstError == null) firstError = failure
            }
        }
        return ParsedArgument(null, null, index, firstError)
    }

    private fun suggestions(current: CommandNode, sender: CommandSender, prefix: String,
        values: Map<CommandArgument<*>, Any?>): List<String> {
        val suggestions = TreeSet<String>(String.CASE_INSENSITIVE_ORDER)
        val normalized = prefix.lowercase(Locale.ROOT)
        for (child in current.children) {
            if (!isAccessible(sender, child)) continue
            if (child.literal != null && child.literal.startsWith(normalized)) suggestions.add(child.literal)
            else child.argument?.let { argument ->
                suggestions.addAll(argument.suggest(SuggestionContext(CommandContextImpl(sender, spec.name(), values), prefix), players))
            }
        }
        if (suggestions.isEmpty()) nearestLiteral(current, sender, prefix)?.let { suggestions.add(it) }
        return ArrayList(suggestions)
    }

    private fun restriction(sender: CommandSender, node: CommandNode): CommandResult? = when {
        node.permission != null && !sender.hasPermission(requireNotNull(node.permission)) ->
            CommandResult.message(CommandResult.Status.NO_PERMISSION, message(sender, CommandMessageKeys.NO_PERMISSION))
        node.playerOnly && sender !is Player ->
            CommandResult.message(CommandResult.Status.PLAYER_ONLY, message(sender, CommandMessageKeys.PLAYER_ONLY))
        node.branchAccess?.test(sender) == false ->
            CommandResult.message(CommandResult.Status.NO_PERMISSION, message(sender, CommandMessageKeys.NO_PERMISSION))
        else -> null
    }

    private fun emit(sender: CommandSender, result: CommandResult): CommandResult = result.also {
        it.message()?.let { text -> output.send(sender, text) }
    }

    private fun errorAt(sender: CommandSender, args: Array<String>, index: Int, detail: RichText,
        suggestions: List<String>): RichText {
        val prefix = (listOf("/${spec.name()}") + args.take(index)).joinToString(" ") + " "
        val segments = arrayListOf(
            RichTextSegment(prefix, MessageColor.GRAY, false, null, null),
            RichTextSegment(args[index], MessageColor.RED, false, false, true, false, false, null, null))
        if (index + 1 < args.size) segments.add(RichTextSegment(" " + join(args, index + 1), MessageColor.GRAY, false, null, null))
        segments.add(RichTextSegment(" ← ", MessageColor.RED, false, null, null))
        segments.addAll(detail.segments())
        suggestions.firstOrNull()?.let { suggestion ->
            val replaced = args.toMutableList().apply { set(index, suggestion) }
            val command = "/${spec.name()} ${replaced.joinToString(" ")}"
            segments.add(RichTextSegment.plain("\n"))
            appendWithActions(segments, messages.resolve(sender, CommandMessageKeys.SUGGESTION,
                MessagePlaceholders.of("suggestion", suggestion)),
                TextAction(TextAction.Type.HOVER_TEXT, command), TextAction(TextAction.Type.SUGGEST_COMMAND, command))
        }
        return RichText(segments)
    }

    fun sendMessage(sender: CommandSender, key: String) = output.send(sender, message(sender, key))
    fun defaultHelpPageSize(): Int = HelpRenderer.style(spec.root()).pageSize()
    private fun message(sender: CommandSender, key: String): RichText = messages.resolve(sender, key, MessagePlaceholders.none())
    private fun optionalChild(current: CommandNode, sender: CommandSender): CommandNode? =
        current.children.firstOrNull { it.argument?.isOptional == true && isAccessible(sender, it) }

    private class ParsedArgument(val node: CommandNode?, val value: Any?, val nextIndex: Int, val error: ArgumentException?)

    companion object {
        private const val DEFAULT_HELP_PAGE_SIZE = 8
        private const val MAX_REASON_LENGTH = 200
        private val fallbackLogger = Logger.getLogger(CommandDispatcher::class.java.name)
        private fun failed(): CommandResult = CommandResult.message(CommandResult.Status.FAILED, null)

        @JvmStatic @JvmName("isAccessible") internal fun isAccessible(sender: CommandSender, node: CommandNode): Boolean =
            (node.permission == null || sender.hasPermission(requireNotNull(node.permission))) && (!node.playerOnly || sender is Player) &&
                (node.branchAccess?.test(sender) != false)

        @JvmStatic fun appendWithActions(output: MutableList<RichTextSegment>, text: RichText, hover: TextAction?, click: TextAction?) {
            text.segments().forEach { segment ->
                output.add(RichTextSegment(segment.text(), segment.color(), segment.bold(), segment.italic(),
                    segment.underlined(), segment.strikethrough(), segment.obfuscated(), hover ?: segment.hover(), click ?: segment.click()))
            }
        }

        private fun displayableReason(failure: Throwable): String? {
            var current: Throwable? = failure
            while (current != null) {
                if (generateSequence(current.javaClass as Class<*>) { it.superclass }
                    .any { it.name == "me.kzheart.klib.config.ConfigException" }) return sanitizeReason(current.message)
                current = current.cause.takeUnless { it === current }
            }
            return null
        }
        private fun sanitizeReason(message: String?): String? {
            if (message == null) return null
            val cleaned = buildString {
                var index = 0
                while (index < message.length) {
                    val char = message[index++]
                    if (char == '§') index++ else append(if (char == '\n' || char == '\r') ' ' else char)
                }
            }.trim()
            if (cleaned.isEmpty()) return null
            return if (cleaned.length <= MAX_REASON_LENGTH) cleaned else cleaned.take(MAX_REASON_LENGTH - 1) + "…"
        }
        private fun findLiteral(current: CommandNode, token: String): CommandNode? =
            current.children.firstOrNull { it.literal?.equals(token, ignoreCase = true) == true }
        private fun nearestLiteral(current: CommandNode, sender: CommandSender, input: String): String? {
            var nearest: String? = null
            var distance = Int.MAX_VALUE
            for (child in current.children) {
                val literal = child.literal ?: continue
                if (!isAccessible(sender, child)) continue
                val candidate = levenshtein(input.lowercase(Locale.ROOT), literal)
                if (candidate < distance) { distance = candidate; nearest = literal }
            }
            return nearest.takeIf { distance <= maxOf(2, input.length / 3) }
        }
        private fun levenshtein(left: String, right: String): Int {
            var previous = IntArray(right.length + 1) { it }
            var current = IntArray(right.length + 1)
            for (leftIndex in 1..left.length) {
                current[0] = leftIndex
                for (rightIndex in 1..right.length) {
                    val substitution = if (left[leftIndex - 1] == right[rightIndex - 1]) 0 else 1
                    current[rightIndex] = minOf(current[rightIndex - 1] + 1, previous[rightIndex] + 1,
                        previous[rightIndex - 1] + substitution)
                }
                val swap = previous; previous = current; current = swap
            }
            return previous[right.length]
        }
        private fun join(args: Array<String>, from: Int): String = args.drop(from).joinToString(" ")
        private fun stripEmptyTokens(args: Array<String>, keepTrailing: Boolean): Array<String> =
            args.filterIndexed { index, token -> token.isNotEmpty() || keepTrailing && index == args.lastIndex }.toTypedArray()
        private fun requireInvocation(args: Array<String>) {
            @Suppress("SENSELESS_COMPARISON")
            require(args.none { it == null }) { "args must not contain null" }
        }
    }
}
