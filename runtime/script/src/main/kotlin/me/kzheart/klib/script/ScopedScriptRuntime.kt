package me.kzheart.klib.script

import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope
import me.kzheart.klib.script.kether.core.SimpleQuestContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor

/** 跟踪根结果及分离的 async 动作，统一取消和作用域释放。清理在调用线程同步执行。 */
class ScopedScriptRuntime(private val scope: Scope, registry: StatementRegistry,
    unknownResolver: UnknownStatementResolver?, continuationExecutor: Executor, toleranceParser: Boolean) : ScriptEngine, AutoCloseable {
    private val lock = Any()
    private val active = HashSet<Execution>()
    private var closed = false
    private val engine = KetherScriptEngine(registry, unknownResolver, continuationExecutor, toleranceParser, this)
    private val disposal = Disposable { close() }
    init { scope.install(disposal) }

    fun engine(): KetherScriptEngine = engine
    fun validate(script: String, context: ScriptContext) { checkOpen(); engine.validate(script, context) }
    override fun eval(script: String, context: ScriptContext): CompletionStage<Any?> { checkOpen(); return engine.eval(script, context) }
    fun evalChecked(script: String, context: ScriptContext): CompletionStage<Any?> { checkOpen(); return engine.evalChecked(script, context) }
    override fun evalCondition(script: String, context: ScriptContext): CompletionStage<Boolean> { checkOpen(); return engine.evalCondition(script, context) }
    fun cancelPending() { synchronized(lock) { active.toList() }.forEach { it.cancel() } }
    override fun close() {
        synchronized(lock) { if (closed) return; closed = true }
        cancelPending()
        scope.remove(disposal)
    }
    fun track(context: SimpleQuestContext, result: CompletableFuture<Any?>, idle: CompletableFuture<Void>): Boolean {
        val execution = Execution(context, result)
        synchronized(lock) {
            if (!closed) {
                active.add(execution)
                idle.whenComplete { _, _ -> synchronized(lock) { active.remove(execution) } }
                return true
            }
        }
        execution.cancel()
        return false
    }
    private fun checkOpen() { synchronized(lock) { check(!closed) { "Script runtime is closed" } } }
    fun activeExecutionCount(): Int = synchronized(lock) { active.size }
    private class Execution(private val context: SimpleQuestContext, private val result: CompletableFuture<Any?>) {
        fun cancel() { result.cancel(false); context.terminate() }
    }
}
