/* Kether action semantics: Copyright (c) 2018 Bkm016, MIT. See THIRD_PARTY_NOTICES.md. */
package me.kzheart.klib.script

import me.kzheart.klib.script.KetherSupport.Companion.action
import me.kzheart.klib.script.KetherSupport.Companion.bool
import me.kzheart.klib.script.KetherSupport.Companion.completed
import me.kzheart.klib.script.KetherSupport.Companion.consume
import me.kzheart.klib.script.KetherSupport.Companion.elements
import me.kzheart.klib.script.KetherSupport.Companion.follow
import me.kzheart.klib.script.KetherSupport.Companion.integer
import me.kzheart.klib.script.KetherSupport.Companion.now
import me.kzheart.klib.script.KetherSupport.Companion.run
import me.kzheart.klib.script.KetherSupport.Companion.setRoot
import me.kzheart.klib.script.KetherSupport.Companion.takeBreak
import me.kzheart.klib.script.KetherSupport.Companion.text
import me.kzheart.klib.script.kether.core.ArgTypes
import me.kzheart.klib.script.kether.core.ExitStatus
import me.kzheart.klib.script.kether.core.ParsedAction
import me.kzheart.klib.script.kether.core.Quest
import me.kzheart.klib.script.kether.core.QuestActionParser as NativeParser
import me.kzheart.klib.script.kether.core.QuestContext
import me.kzheart.klib.script.kether.core.QuestFuture
import me.kzheart.klib.script.kether.core.QuestReader
import me.kzheart.klib.script.kether.core.SimpleReader
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/** 流程语句只负责解析；迭代状态和同步/异步续接规则下沉到执行对象。 */
class FlowScriptActions private constructor() {
    companion object {
        @JvmStatic fun install(registry: StatementRegistry) {
            registry.registerBuiltinKether("import", NativeParser.of { reader ->
                readerNamespace(reader, "import").addNamespace(reader.nextToken()); now { null }
            })
            registry.registerBuiltinKether("release", NativeParser.of { reader ->
                readerNamespace(reader, "release").removeNamespace(reader.nextToken()); now { null }
            })
            registry.registerBuiltinKether("pause", NativeParser.of { action { CompletableFuture() } })
            val exit = NativeParser.of { now { frame ->
                if (!frame.context().exitStatus.isPresent) frame.context().setExitStatus(ExitStatus.paused())
                null
            } }
            listOf("exit", "stop", "terminate").forEach { registry.registerBuiltinKether(it, exit) }
            log(registry, Level.INFO, "log", "print", "info")
            log(registry, Level.WARNING, "warn", "warning")
            log(registry, Level.SEVERE, "error", "severe")
            val wait = NativeParser.of { reader ->
                val duration = reader.next(ArgTypes.DURATION)
                action { frame ->
                    val context = CoreScriptRuntime.context(frame)
                    val source = context.requireService(DelayScheduler::class.java).delay(duration).toCompletableFuture()
                    follow(frame, source) {
                        val sender = context.sender().orElse(null)
                        val query = context.service(ScriptSenderQuery::class.java).orElse(null)
                        if (sender != null && query != null && query.isPlayer(sender) && !query.isOnline(sender) && !frame.context().exitStatus.isPresent) {
                            frame.context().setExitStatus(ExitStatus.paused())
                        }
                        completed(null)
                    }.also { ScriptFutures.cancelWith(it, source) }
                }
            }
            registry.registerBuiltinKether("wait", wait)
            registry.registerBuiltinKether("sleep", wait)
            registry.registerBuiltinKether("async", NativeParser.of { reader ->
                val async = reader.nextValue<Any?>()
                now { frame -> QuestFuture(async, run(frame, async)) }
            })
            registry.registerBuiltinKether("await", NativeParser.of { reader ->
                val awaited = reader.nextValue<Any?>()
                action { frame -> await(frame, awaited) }
            })
            registry.registerBuiltinKether("await_all", NativeParser.of { reader ->
                val actions = reader.next(ArgTypes.listOf(ArgTypes.ACTION))
                action { frame -> CompletableFuture.allOf(*start(frame, actions)).thenApply { null } }
            })
            registry.registerBuiltinKether("await_any", NativeParser.of { reader ->
                val actions = reader.next(ArgTypes.listOf(ArgTypes.ACTION))
                action { frame -> CompletableFuture.anyOf(*start(frame, actions)) }
            })
            registry.registerBuiltinKether("call", NativeParser.of { reader ->
                val label = reader.nextToken()
                action { frame ->
                    val child = frame.newFrame(label)
                    child.setNext(block(frame, label)); frame.addClosable(child); child.run()
                }
            })
            registry.registerBuiltinKether("goto", NativeParser.of { reader ->
                val label = reader.nextToken(); now { frame -> frame.setNext(block(frame, label)); null }
            })
            registry.registerBuiltinKether("repeat", NativeParser.of { reader ->
                val times = reader.nextValue<Any?>()
                val repeated = reader.nextValue<Any?>()
                action { frame -> follow(frame, run(frame, times)) { count ->
                    val futures = ArrayList<CompletableFuture<Any?>>()
                    for (index in 0 until integer(count)) futures.add(run(frame, repeated))
                    CompletableFuture.allOf(*futures.toTypedArray()).thenApply { null }
                } }
            })
            registry.registerBuiltinKether("seq", NativeParser.of { reader ->
                val actions = reader.next(ArgTypes.listOf(ArgTypes.ACTION))
                action { frame -> sequence(frame, actions, 0, null) }
            })
            registry.registerBuiltinKether("break", NativeParser.of { now { frame -> setRoot(frame, KetherSupport.BREAK_LOOP, true); null } })
            iteration(registry, "for", "then", false)
            iteration(registry, "map", "with", true)
            registry.registerBuiltinKether("while", NativeParser.of { reader ->
                val condition = reader.nextValue<Any?>()
                reader.expect("then")
                val body = reader.nextValue<Any?>()
                action { frame -> WhileLoop(frame, condition, body).start() }
            })
            registry.registerBuiltinKether("optional", NativeParser.of { reader ->
                val value = reader.nextValue<Any?>()
                val otherwise = if (consume(reader, "else")) reader.nextValue<Any?>() else null
                action { frame -> follow(frame, run(frame, value)) { result ->
                    if (result != null || otherwise == null) completed(result) else run(frame, otherwise)
                } }
            })
            registry.registerBuiltinKether("pass", NativeParser.of { now { "" } })
            val variables = NativeParser.of { now { frame -> ScriptFrames.variables(frame).keys.filterNot { ScriptFrames.isInternal(it) } } }
            registry.registerBuiltinKether("vars", variables)
            registry.registerBuiltinKether("variables", variables)
        }

        private fun await(frame: QuestContext.Frame, awaited: ParsedAction<*>): CompletableFuture<Any?> {
            val result = CompletableFuture<Any?>()
            val input = run(frame, awaited)
            ScriptFutures.cancelWith(result, input)
            val completion = follow(frame, input) { value ->
                if (value !is QuestFuture<*>) completed(value)
                else {
                    val deferred = value.future ?: throw IllegalStateException("Awaited action has no active future")
                    ScriptFutures.cancelWith(result, deferred)
                    deferred.thenApply<Any?> { it }
                }
            }
            ScriptFutures.cancelWith(result, completion)
            completion.whenComplete { value, failure ->
                if (failure == null) result.complete(value)
                else if (!ScriptFutures.cancelIfNeeded(result, failure)) result.completeExceptionally(ScriptFutures.unwrap(failure))
            }
            return result
        }
        private fun iteration(registry: StatementRegistry, name: String, keyword: String, collect: Boolean) {
            registry.registerBuiltinKether(name, NativeParser.of { reader ->
                val key = reader.nextToken(); reader.expect("in")
                val values = reader.nextValue<Any?>(); reader.expect(keyword)
                val body = reader.nextValue<Any?>()
                action { frame -> Iteration(frame, key, body, collect).start(values) }
            })
        }
        private fun log(registry: StatementRegistry, level: Level, vararg names: String) {
            val parser = NativeParser.of { reader ->
                val message = reader.nextValue<Any?>()
                action { frame -> follow(frame, run(frame, message)) { value ->
                    CoreScriptRuntime.context(frame).requireService(ScriptLogger::class.java).log(level, text(value)); completed(null)
                } }
            }
            names.forEach { registry.registerBuiltinKether(it, parser) }
        }
        private fun readerNamespace(reader: QuestReader, statement: String): SimpleReader = reader as? SimpleReader
            ?: throw IllegalArgumentException("$statement requires a native QuestReader")
        private fun block(frame: QuestContext.Frame, label: String): Quest.Block = frame.context().quest.blocks[label]
            ?: throw IllegalStateException("block $label not found")
        private fun start(frame: QuestContext.Frame, actions: List<ParsedAction<*>>): Array<CompletableFuture<*>> =
            actions.map { run(frame, it) }.toTypedArray()
        private fun sequence(frame: QuestContext.Frame, actions: List<ParsedAction<*>>, index: Int, last: Any?): CompletableFuture<Any?> {
            if (index >= actions.size) return completed(last)
            val result = CompletableFuture<Any?>()
            onComplete(frame, run(frame, actions[index]), result) { value, failure ->
                if (failure != null) result.complete(last)
                else sequence(frame, actions, index + 1, value).whenComplete { next, error ->
                    if (error == null) result.complete(next) else result.completeExceptionally(error)
                }
            }
            return result
        }
        private fun <T> onComplete(frame: QuestContext.Frame, input: CompletableFuture<T>, result: CompletableFuture<*>, continuation: (T, Throwable?) -> Unit) {
            val observed = if (input.isDone) input.whenComplete(continuation) else input.whenCompleteAsync(continuation, frame.context().executor)
            observed.whenComplete { _, failure -> if (failure != null && !result.isDone) result.completeExceptionally(ScriptFutures.unwrap(failure)) }
        }
    }

    /** for/map 生命周期内临时变量和结果由单一对象拥有，所有结束路径统一清理。 */
    private class Iteration(private val frame: QuestContext.Frame, private val key: String,
        private val body: ParsedAction<*>, collect: Boolean) {
        private val results: MutableList<Any?>? = if (collect) ArrayList() else null
        private val future = CompletableFuture<Any?>()
        fun start(values: ParsedAction<*>): CompletableFuture<Any?> {
            onComplete(frame, run(frame, values), future) { source, failure ->
                if (failure != null) finish() else next(elements(source), 0)
            }
            return future
        }
        private fun next(items: List<Any?>, start: Int) {
            for (index in start until items.size) {
                val element = items[index]
                if (element is Map.Entry<*, *>) {
                    frame.variables().set("$key-key", element.key)
                    frame.variables().set("$key-value", element.value)
                }
                frame.variables().set(key, element)
                val step = run(frame, body)
                if (!step.isDone) {
                    onComplete(frame, step, future) { value, failure ->
                        if (failure != null) finish()
                        else {
                            if (value != null) results?.add(value)
                            if (takeBreak(frame)) finish() else next(items, index + 1)
                        }
                    }
                    return
                }
                val value = try { step.join() } catch (_: RuntimeException) { finish(); return }
                if (value != null) results?.add(value)
                if (takeBreak(frame)) { finish(); return }
            }
            finish()
        }
        private fun finish() {
            frame.variables().remove(key); frame.variables().remove("$key-key"); frame.variables().remove("$key-value")
            future.complete(results)
        }
    }

    /** 已完成迭代保持循环快路径，只有异步动作挂起续接，避免递归栈增长。 */
    private class WhileLoop(private val frame: QuestContext.Frame, private val condition: ParsedAction<*>, private val body: ParsedAction<*>) {
        private val future = CompletableFuture<Any?>()
        fun start(): CompletableFuture<Any?> { loop(); return future }
        private fun loop() {
            while (true) {
                val check = run(frame, condition)
                if (!check.isDone) {
                    onComplete(frame, check, future) { value, failure ->
                        if (failure != null || !bool(value)) future.complete(null) else afterCondition()
                    }
                    return
                }
                val value = try { check.join() } catch (_: RuntimeException) { future.complete(null); return }
                if (!bool(value)) { future.complete(null); return }
                val step = run(frame, body)
                if (!step.isDone) {
                    onComplete(frame, step, future) { _, failure ->
                        if (failure != null || takeBreak(frame)) future.complete(null) else loop()
                    }
                    return
                }
                try { step.join() } catch (_: RuntimeException) { future.complete(null); return }
                if (takeBreak(frame)) { future.complete(null); return }
            }
        }
        private fun afterCondition() {
            onComplete(frame, run(frame, body), future) { _, failure ->
                if (failure != null || takeBreak(frame)) future.complete(null) else loop()
            }
        }
    }
}
