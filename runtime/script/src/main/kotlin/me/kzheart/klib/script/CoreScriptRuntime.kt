package me.kzheart.klib.script

import me.kzheart.klib.script.kether.core.ActionProperties
import me.kzheart.klib.script.kether.core.ParsedAction
import me.kzheart.klib.script.kether.core.Quest
import me.kzheart.klib.script.kether.core.QuestAction
import me.kzheart.klib.script.kether.core.QuestActionParser as NativeParser
import me.kzheart.klib.script.kether.core.QuestContext
import me.kzheart.klib.script.kether.core.QuestFuture
import me.kzheart.klib.script.kether.core.QuestReader
import me.kzheart.klib.script.kether.core.SimpleQuestService
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.Objects
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Function
import java.util.regex.Pattern

/** 连接公开语句、Kether 编译和执行；缓存与源文本预算各自拥有完整不变量。 */
class CoreScriptRuntime(private val registry: StatementRegistry, private val unknownResolver: UnknownStatementResolver?,
    private val continuationExecutor: Executor, private val toleranceParser: Boolean, private val owner: ScopedScriptRuntime?) {
    private val cache = CompilationCache()
    private val compilationCount = AtomicLong()

    fun eval(source: String, scriptContext: ScriptContext): CompletionStage<Any?> = eval(source, scriptContext, EvaluationState())
    private fun eval(source: String, scriptContext: ScriptContext, state: EvaluationState): CompletionStage<Any?> {
        val quest = try { compiledQuest(source, scriptContext.namespaces()) }
            catch (failure: RuntimeException) { return failed(failure) }
            catch (failure: StackOverflowError) { return failed(compilationFailure("syntax nesting exhausted the parser stack", failure)) }
        return execute(quest, scriptContext, state)
    }
    fun validate(source: String, scriptContext: ScriptContext) {
        try { compiledQuest(source, scriptContext.namespaces()) }
        catch (failure: StackOverflowError) { throw compilationFailure("syntax nesting exhausted the parser stack", failure) }
    }
    fun evalChecked(source: String, scriptContext: ScriptContext): CompletionStage<Any?> {
        val quest = try { compiledQuest(source, scriptContext.namespaces()) }
            catch (failure: StackOverflowError) { throw compilationFailure("syntax nesting exhausted the parser stack", failure) }
        return execute(quest, scriptContext, EvaluationState())
    }

    private fun execute(quest: Quest, scriptContext: ScriptContext, state: EvaluationState): CompletionStage<Any?> {
        val service = SimpleQuestService(continuationExecutor)
        service.setPropertyAccessor(ScriptProperties::read)
        val context = service.newContext(quest)
        val result = CompletableFuture<Any?>()
        try {
            val before = scriptContext.variables()
            val borrowed = IdentityHashMap<Any?, Any?>()
            for ((key, value) in before) if (!ScriptFrames.isInternal(key)) {
                context.rootFrame().variables().set(key, borrowVariable(value, borrowed))
            }
            context.rootFrame().variables().set(CONTEXT_VARIABLE, scriptContext)
            context.rootFrame().variables().set(EVALUATION_STATE_VARIABLE, state)
            result.whenComplete { _, _ -> if (result.isCancelled) context.terminate() }
            if (owner != null) {
                val idle = context.retainDetachedActions()
                idle.whenComplete { _, _ -> service.close() }
                if (!owner.track(context, result, idle)) return result
            }
            context.runActions().whenComplete { value, failure ->
                var completionFailure = failure
                try {
                    val after = LinkedHashMap(ScriptFrames.variables(context.rootFrame()))
                    for (entry in after.entries) if (borrowed.containsKey(entry.value)) entry.setValue(borrowed[entry.value])
                    synchronizeHostVariables(scriptContext, before, after)
                } catch (syncFailure: RuntimeException) {
                    if (failure != null) syncFailure.addSuppressed(failure)
                    completionFailure = syncFailure
                } finally { if (owner == null) service.close() }
                if (completionFailure == null) result.complete(value)
                else if (!ScriptFutures.cancelIfNeeded(result, completionFailure)) result.completeExceptionally(completionFailure)
                if (owner != null && completionFailure != null) context.terminate()
            }
        } catch (failure: RuntimeException) {
            result.completeExceptionally(failure)
            context.terminate()
            service.close()
        } catch (failure: StackOverflowError) {
            result.completeExceptionally(actionFailure(failure, scriptContext,
                StatementCall("kether", emptyList(), 1, 1, state::evalNested)))
            context.terminate()
            service.close()
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun borrowVariable(value: Any?, borrowed: MutableMap<Any?, Any?>): Any? {
        if (value is QuestFuture<*> && value.future != null) {
            val original = value as QuestFuture<Any?>
            val view = QuestFuture(original.action, original.future)
            borrowed[view] = original
            return view
        }
        return value
    }

    private fun compiledQuest(source: String, namespaces: List<String>): Quest {
        SourceBudget.validate(source)
        val key = CacheKey(SourceBudget.normalize(source), namespaces)
        val snapshot = registry.snapshot()
        cache.get(snapshot.version(), key)?.let { return it }
        val compiler = SimpleQuestService(toleranceParser)
        val compiled: Quest
        try {
            installStatements(compiler, key.namespaces, snapshot.entries(), source, if (SourceBudget.beginsWithDefinition(source)) 0 else 1)
            compiled = compiler.load("klib-${SCRIPT_IDS.incrementAndGet()}", key.source, key.namespaces)
            var actions = 0
            for (block in compiled.blocks.values) {
                actions += block.actions.size
                if (actions > MAX_ACTIONS) throw compilationFailure("action count exceeds $MAX_ACTIONS", null)
            }
            compilationCount.incrementAndGet()
        } finally { compiler.close() }
        if (registry.version() == snapshot.version()) cache.put(snapshot.version(), key, compiled)
        return compiled
    }
    fun cachedScriptCount(): Int = cache.size()
    fun compilationCount(): Long = compilationCount.get()

    private fun installStatements(service: SimpleQuestService, namespaces: List<String>, entries: List<StatementRegistry.EntryView>,
        source: String, lineOffset: Int) {
        for (entry in entries) {
            val parser = entry.ketherParser ?: upperParser("${entry.namespace}:${entry.name}", entry.name, namespaces,
                lineOffset, entry.parser === BuiltInStatements.COMMAND)
            service.registry.registerAction(entry.namespace, entry.name, parser)
        }
        if (unknownResolver is KetherParserResolver) {
            service.registry.setFallbackParser { name, selected ->
                if (name.trim { it <= ' ' }.isEmpty()) null
                else unknownResolver.parser(name, selected).let { if (toleranceParser) tolerant(it, name) else it }
            }
        } else if (unknownResolver != null) {
            val matcher = ACTION_START.matcher(source)
            while (matcher.find()) {
                val name = matcher.group(1).lowercase(Locale.ROOT)
                if (name != "def" && !containsEntry(entries, name, namespaces)) service.registry.registerAction("kether", name, unknownParser(name))
            }
        }
    }

    private fun upperParser(lookupName: String, name: String, namespaces: List<String>, lineOffset: Int, builtinCommand: Boolean): NativeParser {
        if (name == "if") return conditionalParser()
        if (name == "namespace") return namespaceParser()
        if (builtinCommand) return commandParser(lookupName, name, namespaces, lineOffset)
        val statement = statementParser(lookupName, name, namespaces, lineOffset)
        if (name == "join") return NativeParser.of { reader ->
            if (reader.hasNext() && reader.peek() == '[') CollectionScriptActions.joinList(reader) else statement.resolve(reader)
        }
        if (name == "get") return NativeParser.of { reader ->
            val mark = reader.index
            if (reader.hasNext() && reader.nextToken() == "property") {
                val key = reader.nextToken()
                KetherSupport.expects(reader, "from", "in")
                val instance = reader.nextValue<Any?>()
                KetherSupport.action { frame -> KetherSupport.follow(frame, KetherSupport.run(frame, instance)) { value ->
                    KetherSupport.completed(ScriptProperties.read(frame, value, key))
                } }
            } else { reader.index = mark; statement.resolve(reader) }
        }
        return statement
    }

    private fun statementParser(lookupName: String, name: String, namespaces: List<String>, lineOffset: Int): NativeParser = NativeParser.of { reader ->
        val arguments = readArguments(name, reader)
        object : QuestAction<Any?>() {
            override fun process(frame: QuestContext.Frame): CompletableFuture<Any?> {
                val context = context(frame)
                val position = sourcePosition(frame, lineOffset)
                val parser = registry.resolve(lookupName, namespaces).orElse(null)
                    ?: return failed(IllegalArgumentException("Unknown statement: $name"))
                val call = StatementCall(name, arguments, position.line, position.column, evaluationState(frame)::evalNested)
                val execution = try { parser.execute(call, context) }
                    catch (failure: RuntimeException) { return failed(actionFailure(failure, context, call)) }
                    catch (failure: StackOverflowError) { return failed(actionFailure(failure, context, call)) }
                if (execution == null) return failed(actionFailure(IllegalStateException("statement returned null stage"), context, call))
                val result = CompletableFuture<Any?>()
                ScriptFutures.cancelWith(result, execution)
                execution.whenComplete { value, failure ->
                    if (!ScriptFutures.cancelIfNeeded(result, failure)) {
                        if (failure == null) result.complete(value)
                        else result.completeExceptionally(actionFailure(unwrap(failure), context, call))
                    }
                }
                return result
            }
        }
    }

    private fun commandParser(lookupName: String, name: String, namespaces: List<String>, lineOffset: Int): NativeParser = NativeParser.of { reader ->
        require(reader.hasNext() && reader.peek() != '}') { "Expected command value" }
        val start = reader.index
        val prefix = reader.peek()
        val first = reader.nextToken()
        reader.index = start
        val expression = prefix in "*&{'\"" || containsEntry(registry.snapshot().entries(), first.lowercase(Locale.ROOT), namespaces)
        if (!expression) statementParser(lookupName, name, namespaces, lineOffset).resolve(reader)
        else {
            val command = reader.nextValue<Any?>()
            val mode = if (!reader.hasLineBreakBeforeNextToken() && KetherSupport.consume(reader, "as", "by", "with")) {
                require(!reader.hasLineBreakBeforeNextToken() && reader.hasNext() && reader.peek() != '}') { "Expected command sender after as" }
                BuiltInStatements.CommandMode.of(reader.nextToken())
            } else BuiltInStatements.CommandMode.PLAYER
            object : QuestAction<Any?>() {
                override fun process(frame: QuestContext.Frame): CompletableFuture<Any?> {
                    val context = context(frame)
                    val position = sourcePosition(frame, lineOffset)
                    val call = StatementCall(name, emptyList(), position.line, position.column, evaluationState(frame)::evalNested)
                    val result = CompletableFuture<Any?>()
                    try {
                        val value = frame.newFrame(command).run<Any?>()
                        val dispatch = Function<Any?, Any?> { input ->
                            BuiltInStatements.dispatchCommand(context, InlineValues.interpolate(input.toString(), context), mode)
                        }
                        val execution = if (value.isDone) value.thenApply(dispatch) else value.thenApplyAsync(dispatch, frame.context().executor)
                        execution.whenComplete { output, failure ->
                            if (failure == null) result.complete(output)
                            else result.completeExceptionally(actionFailure(unwrap(failure), context, call))
                        }
                    } catch (failure: RuntimeException) { result.completeExceptionally(actionFailure(failure, context, call)) }
                    catch (failure: StackOverflowError) { result.completeExceptionally(actionFailure(failure, context, call)) }
                    return result
                }
            }
        }
    }

    private fun conditionalParser(): NativeParser = NativeParser.of { reader ->
        val condition = reader.nextParsedAction()
        reader.expect("then")
        val accepted = reader.nextParsedAction()
        val rejected = if (StructuredScriptActions.consume(reader, "else")) reader.nextParsedAction() else null
        object : QuestAction<Any?>() {
            override fun process(frame: QuestContext.Frame): CompletableFuture<Any?> =
                KetherSupport.follow(frame, frame.newFrame(condition).run<Any?>()) { value ->
                    val selected = if (InlineValues.truthy(value)) accepted else rejected
                    if (selected == null) CompletableFuture.completedFuture(null) else frame.newFrame(selected).run()
                }
        }
    }
    private fun namespaceParser(): NativeParser = NativeParser.of { reader ->
        val body = reader.nextParsedAction(reader.nextToken())
        object : QuestAction<Any?>() {
            override fun process(frame: QuestContext.Frame): CompletableFuture<Any?> = frame.newFrame(body).run()
        }
    }
    private fun unknownParser(name: String): NativeParser = NativeParser.of { reader ->
        val statement = (listOf(name) + readRemaining(reader)).joinToString(" ")
        object : QuestAction<Any?>() {
            override fun process(frame: QuestContext.Frame): CompletableFuture<Any?> = try {
                unknownResolver!!.resolve(statement, context(frame)).toCompletableFuture()
            } catch (failure: RuntimeException) { failed(failure) }
            catch (failure: StackOverflowError) { failed(failure) }
        }
    }

    private inner class EvaluationState {
        private val nested = AtomicInteger()
        fun evalNested(source: String, context: ScriptContext): CompletionStage<Any?> {
            if (nested.incrementAndGet() > MAX_NESTED_EVALUATIONS) {
                nested.decrementAndGet()
                return failed(IllegalStateException("script nesting exceeds $MAX_NESTED_EVALUATIONS"))
            }
            val execution = try { eval(source, context, this) }
                catch (failure: RuntimeException) { nested.decrementAndGet(); return failed(failure) }
            val result = CompletableFuture<Any?>()
            ScriptFutures.cancelWith(result, execution)
            execution.whenComplete { value, failure ->
                nested.decrementAndGet()
                if (!ScriptFutures.cancelIfNeeded(result, failure)) {
                    if (failure == null) result.complete(value) else result.completeExceptionally(unwrap(failure))
                }
            }
            return result
        }
    }

    private class CacheKey(val source: String, namespaces: List<String>) {
        val namespaces: List<String> = Collections.unmodifiableList(ArrayList(namespaces))
        override fun equals(other: Any?): Boolean = other is CacheKey && source == other.source && namespaces == other.namespaces
        override fun hashCode(): Int = 31 * source.hashCode() + namespaces.hashCode()
    }
    /** 同步拥有 LRU、版本单调性和双预算；编译工作永远在锁外。 */
    private class CompilationCache {
        private val entries = LinkedHashMap<CacheKey, Quest>(16, 0.75f, true)
        private var version = -1L
        private var sourceChars = 0
        @Synchronized fun get(registryVersion: Long, key: CacheKey): Quest? {
            if (key.source.length > MAX_CACHEABLE_SOURCE_CHARS || registryVersion < version) return null
            align(registryVersion)
            return entries[key]
        }
        @Synchronized fun put(registryVersion: Long, key: CacheKey, quest: Quest) {
            if (key.source.length > MAX_CACHEABLE_SOURCE_CHARS || registryVersion < version) return
            align(registryVersion)
            if (entries.put(key, quest) == null) sourceChars += key.source.length
            val iterator = entries.iterator()
            while ((entries.size > MAX_CACHE_ENTRIES || sourceChars > MAX_CACHE_SOURCE_CHARS) && iterator.hasNext()) {
                sourceChars -= iterator.next().key.source.length
                iterator.remove()
            }
        }
        @Synchronized fun size(): Int = entries.size
        private fun align(registryVersion: Long) {
            if (registryVersion != version) { entries.clear(); sourceChars = 0; version = registryVersion }
        }
    }
    private class SourcePosition(val line: Int, val column: Int)

    companion object {
        private const val CONTEXT_VARIABLE = "~klib:context"
        private const val EVALUATION_STATE_VARIABLE = "~klib:evaluation-state"
        private val ACTION_START = Pattern.compile("(?m)(?:^|[\\n;{])\\s*([A-Za-z_][A-Za-z0-9_:.-]*)")
        private val SCRIPT_IDS = AtomicLong()
        private const val MAX_NESTED_EVALUATIONS = 64
        private const val MAX_SOURCE_CHARS = 256 * 1024
        private const val MAX_ACTIONS = 10_000
        private const val MAX_SYNTAX_DEPTH = 128
        private const val MAX_CACHE_ENTRIES = 128
        private const val MAX_CACHE_SOURCE_CHARS = 1024 * 1024
        private const val MAX_CACHEABLE_SOURCE_CHARS = 64 * 1024

        @JvmStatic fun context(frame: QuestContext.Frame): ScriptContext =
            (frame.variables().getOrNull<ScriptContext>(CONTEXT_VARIABLE)
                ?: throw IllegalStateException("Kether frame has no ScriptContext")).atFrame(frame)
        @JvmStatic fun evalNested(frame: QuestContext.Frame, source: String): CompletionStage<Any?> =
            evaluationState(frame).evalNested(source, context(frame))
        private fun evaluationState(frame: QuestContext.Frame): EvaluationState =
            frame.variables().getOrNull<EvaluationState>(EVALUATION_STATE_VARIABLE)
                ?: throw IllegalStateException("Kether frame has no evaluation state")
        private fun tolerant(remote: NativeParser, name: String): NativeParser = NativeParser.of { reader ->
            val index = reader.index
            try { remote.resolve(reader) } catch (_: UnresolvedActionException) {
                reader.index = index
                KetherSupport.now { name }
            }
        }
        private fun containsEntry(entries: List<StatementRegistry.EntryView>, name: String, namespaces: List<String>): Boolean {
            val separator = name.indexOf(':')
            if (separator > 0) return entries.any { it.namespace == name.substring(0, separator) && it.name == name.substring(separator + 1) }
            return entries.any { it.name == name && (it.namespace in namespaces || it.namespace == "global") }
        }
        private fun synchronizeHostVariables(context: ScriptContext, before: Map<String, Any?>, after: Map<String, Any?>) {
            for ((key, value) in after) if (!before.containsKey(key) || !Objects.equals(before[key], value)) context.writeVariable(key, value)
            for (name in before.keys) if (!ScriptFrames.isInternal(name) && !after.containsKey(name)) context.removeVariable(name)
        }
        private fun sourcePosition(frame: QuestContext.Frame, lineOffset: Int): SourcePosition {
            val action = frame.currentAction().orElse(null) ?: return SourcePosition(1, 1)
            return SourcePosition(maxOf(1, action.get(ActionProperties.LINE, 1) - lineOffset), action.get(ActionProperties.COLUMN, 1))
        }
        private fun actionFailure(failure: Throwable, context: ScriptContext, call: StatementCall): ScriptException =
            if (failure is ScriptException) failure else ScriptException("action-failed",
                ScriptMessages.text(context.locale(), "action-failed", call.line(), call.column(), call.name(),
                    failure.message ?: failure.javaClass.simpleName), call.line(), call.column(), failure)
        private fun readArguments(name: String, reader: QuestReader): List<String> {
            val arguments = ArrayList<String>()
            when (name) {
                "tell", "get", "unset", "not", "perm", "delay", "literal" -> arguments.add(reader.nextToken())
                "type", "eq", "ne", "gt", "gte", "lt", "lte" -> repeat(2) { arguments.add(reader.nextToken()) }
                "set" -> {
                    arguments.add(reader.nextToken())
                    val second = reader.nextToken()
                    arguments.add(second)
                    if (second.equals("to", true)) arguments.add(reader.nextToken())
                }
                "check" -> {
                    val source = reader.nextToken()
                    arguments.add(source)
                    repeat(2) { arguments.add(reader.nextToken()) }
                    if (source.equals("papi", true) || source.equals("player", true)) arguments.add(reader.nextToken())
                }
                "all", "any" -> {
                    reader.expect("["); arguments.add("[")
                    while (reader.hasNext() && reader.peek() != ']') arguments.add(reader.nextToken())
                    reader.expect("]"); arguments.add("]")
                }
                else -> arguments.addAll(readRemaining(reader))
            }
            return arguments
        }
        private fun readRemaining(reader: QuestReader): List<String> {
            val arguments = ArrayList<String>()
            while (!reader.hasLineBreakBeforeNextToken() && reader.hasNext() && reader.peek() != '}') arguments.add(reader.nextToken())
            return arguments
        }
        private fun compilationFailure(detail: String, cause: Throwable?): ScriptException =
            ScriptException("compilation-limit", "Script compilation rejected: $detail", 1, 1, cause)
        private fun <T> failed(failure: Throwable): CompletableFuture<T> = CompletableFuture<T>().apply { completeExceptionally(failure) }
        private fun unwrap(failure: Throwable): Throwable = if (failure is CompletionException) failure.cause ?: failure else failure
    }

    /** 语法预算与兼容性正规化不触碰宿主服务，也不进入编译缓存锁。 */
    private object SourceBudget {
        fun normalize(source: String): String {
            val normalized = separators(source).replace(Regex("(?m)^([ \\t]*)#"), "$1//")
            return if (beginsWithDefinition(normalized)) normalized else "def main = {\n$normalized\n}"
        }
        fun beginsWithDefinition(source: String): Boolean {
            var index = 0
            while (index < source.length) {
                val current = source[index]
                if (Character.isWhitespace(current)) index++
                else if (current == '#' || (current == '/' && index + 1 < source.length && source[index + 1] == '/')) {
                    while (index < source.length && source[index] != '\n' && source[index] != '\r') index++
                } else return source.startsWith("def", index) && index + 3 < source.length && Character.isWhitespace(source[index + 3])
            }
            return false
        }
        fun validate(source: String) {
            if (source.length > MAX_SOURCE_CHARS) throw compilationFailure("source length exceeds $MAX_SOURCE_CHARS characters", null)
            var actions = 0
            var depth = 0
            var quote: Char? = null
            var escaped = false
            var lineComment = false
            var actionStart = true
            var index = 0
            while (index < source.length) {
                val current = source[index++]
                if (lineComment) {
                    if (current == '\n' || current == '\r') { lineComment = false; actionStart = true }
                    continue
                }
                if (quote != null) {
                    if (escaped) escaped = false else if (current == '\\') escaped = true else if (current == quote) quote = null
                    continue
                }
                if (current == '/' && index < source.length && source[index] == '/') { lineComment = true; index++; continue }
                when (current) {
                    '#' -> { lineComment = true; continue }
                    '\'', '"' -> { quote = current; actionStart = false; continue }
                    '{' -> {
                        if (++depth > MAX_SYNTAX_DEPTH) throw compilationFailure("syntax nesting exceeds $MAX_SYNTAX_DEPTH", null)
                        actionStart = true; continue
                    }
                    '}' -> { if (depth > 0) depth--; actionStart = false; continue }
                    '\n', '\r', ';' -> { actionStart = true; continue }
                }
                if (Character.isWhitespace(current)) continue
                if (actionStart) {
                    if (++actions > MAX_ACTIONS) throw compilationFailure("action count exceeds $MAX_ACTIONS", null)
                    actionStart = false
                }
            }
        }
        private fun separators(source: String): String = buildString(source.length) {
            var quote: Char? = null
            var escaped = false
            for (current in source) {
                when {
                    escaped -> escaped = false
                    quote != null && current == '\\' -> escaped = true
                    quote != null && current == quote -> quote = null
                    quote == null && (current == '\'' || current == '"') -> quote = current
                }
                append(if (current == ';' && quote == null) '\n' else current)
            }
        }
    }
}
