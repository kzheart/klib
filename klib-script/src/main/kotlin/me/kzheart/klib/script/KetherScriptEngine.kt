package me.kzheart.klib.script

import me.kzheart.klib.script.kether.core.LoadError
import me.kzheart.klib.script.kether.core.LocalizedException
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor

/** Kether 执行入口；编译、执行和校验共享缓存，异常本地化在边界统一完成。 */
class KetherScriptEngine internal constructor(registry: StatementRegistry, unknownResolver: UnknownStatementResolver?,
    continuationExecutor: Executor, toleranceParser: Boolean, owner: ScopedScriptRuntime?) : ScriptEngine {
    private val runtime = CoreScriptRuntime(registry, unknownResolver, continuationExecutor, toleranceParser, owner)
    init { BuiltInStatements.install(registry) }
    constructor(registry: StatementRegistry) : this(registry, null)
    constructor(registry: StatementRegistry, unknownResolver: UnknownStatementResolver?) : this(registry, unknownResolver, EXPLICIT_EXECUTOR_REQUIRED)
    constructor(registry: StatementRegistry, unknownResolver: UnknownStatementResolver?, continuationExecutor: Executor) :
        this(registry, unknownResolver, continuationExecutor, false)
    constructor(registry: StatementRegistry, unknownResolver: UnknownStatementResolver?, continuationExecutor: Executor, toleranceParser: Boolean) :
        this(registry, unknownResolver, continuationExecutor, toleranceParser, null)

    fun validate(script: String, context: ScriptContext) {
        try { runtime.validate(script, context) } catch (failure: RuntimeException) { throw localize(unwrap(failure), context.locale(), script) }
    }
    override fun eval(script: String, context: ScriptContext): CompletionStage<Any?> = localizeExecution(runtime.eval(script, context), script, context)
    fun evalChecked(script: String, context: ScriptContext): CompletionStage<Any?> {
        val execution = try { runtime.evalChecked(script, context) }
            catch (failure: RuntimeException) { throw localize(unwrap(failure), context.locale(), script) }
        return localizeExecution(execution, script, context)
    }
    override fun evalCondition(script: String, context: ScriptContext): CompletionStage<Boolean> {
        val execution = eval(script, context)
        val result = CompletableFuture<Boolean>()
        ScriptFutures.cancelWith(result, execution)
        execution.whenComplete { value, failure ->
            when {
                failure != null -> if (!ScriptFutures.cancelIfNeeded(result, failure)) result.completeExceptionally(failure)
                value is Boolean -> result.complete(value)
                value is Number || value is CharSequence || value == null -> result.complete(InlineValues.truthy(value))
                else -> result.completeExceptionally(ScriptException("invalid-condition",
                    ScriptMessages.text(context.locale(), "invalid-condition", value.toString()), 1, 1, null))
            }
        }
        return result
    }
    fun cachedScriptCount(): Int = runtime.cachedScriptCount()
    fun compilationCount(): Long = runtime.compilationCount()

    private class ContinuationExecutorRequiredException : IllegalStateException("Asynchronous script actions require an explicit continuation Executor") {
        companion object { private const val serialVersionUID = 1L }
    }
    companion object {
        private val EXPLICIT_EXECUTOR_REQUIRED = Executor { throw ContinuationExecutorRequiredException() }
        private fun localizeExecution(execution: CompletionStage<Any?>, script: String, context: ScriptContext): CompletionStage<Any?> {
            val result = CompletableFuture<Any?>()
            ScriptFutures.cancelWith(result, execution)
            execution.whenComplete { value, failure ->
                if (!ScriptFutures.cancelIfNeeded(result, failure)) {
                    if (failure == null) result.complete(value)
                    else result.completeExceptionally(localize(unwrap(failure), context.locale(), script))
                }
            }
            return result
        }
        private fun localize(failure: Throwable, locale: Locale, source: String): ScriptException {
            if (failure is ScriptException) return failure
            if (failure is ContinuationExecutorRequiredException) return ScriptException("continuation-executor-required",
                ScriptMessages.text(locale, "continuation-executor-required"), 1, 1, failure)
            if (failure is LocalizedException) {
                var line = sourceLine(failure)
                val unknown = failure.stream().filter { it.error == LoadError.UNKNOWN_ACTION }.findFirst().orElse(null)
                if (unknown != null) {
                    val name = unknown.params.firstOrNull()?.toString() ?: "?"
                    line = lineOf(source, name, line)
                    return ScriptException("unknown-statement", ScriptMessages.text(locale, "unknown-statement", line, 1, name), line, 1, failure)
                }
                return actionFailure(failure, locale, line, firstAction(source))
            }
            return actionFailure(failure, locale, 1, firstAction(source))
        }
        private fun actionFailure(failure: Throwable, locale: Locale, line: Int, statement: String): ScriptException = ScriptException(
            "action-failed", ScriptMessages.text(locale, "action-failed", line, 1, statement, failure.message ?: failure.javaClass.simpleName), line, 1, failure)
        private fun sourceLine(failure: LocalizedException): Int {
            val block = failure.stream().filter { it.error == LoadError.BLOCK_ERROR }.findFirst().orElse(null) ?: return 1
            return (block.params.getOrNull(1) as? Number)?.let { maxOf(1, it.toInt() - 1) } ?: 1
        }
        private fun lineOf(source: String, token: String, fallback: Int): Int {
            for ((index, line) in source.split(Regex("\\r?\\n")).withIndex()) {
                val trimmed = line.trim { it <= ' ' }
                if (!trimmed.startsWith("#") && !trimmed.startsWith("//") && containsToken(trimmed, token)) return index + 1
            }
            return fallback
        }
        private fun containsToken(source: String, token: String): Boolean {
            val index = source.indexOf(token)
            if (index < 0) return false
            val end = index + token.length
            return (index == 0 || !Character.isJavaIdentifierPart(source[index - 1])) &&
                (end == source.length || !Character.isJavaIdentifierPart(source[end]))
        }
        private fun firstAction(source: String): String {
            for (line in source.split(Regex("[;\\r\\n]+"))) {
                val trimmed = line.trim { it <= ' ' }
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//") || trimmed.startsWith("def ") || trimmed == "}") continue
                return trimmed.takeWhile { !Character.isWhitespace(it) }
            }
            return "kether"
        }
        private fun unwrap(failure: Throwable): Throwable = if (failure is CompletionException) failure.cause ?: failure else failure
    }
}
