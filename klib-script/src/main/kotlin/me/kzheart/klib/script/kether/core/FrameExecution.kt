/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core

import java.util.Collections
import java.util.Optional
import java.util.LinkedList
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/** 帧树和资源释放只有一个实现，关闭时先处理子帧，再处理本帧资源。 */
internal object FrameExecution {
    @JvmStatic fun newFrame(parent: AbstractQuestContext.AbstractFrame, name: String): QuestContext.Frame =
        AbstractQuestContext.SimpleNamedFrame(parent, LinkedList(), AbstractQuestContext.SimpleVarTable(parent), name, parent.context())
            .also { parent.frames.add(it) }
    @JvmStatic fun newFrame(parent: AbstractQuestContext.AbstractFrame, action: ParsedAction<*>): QuestContext.Frame {
        val child = if (action.get(ActionProperties.REQUIRE_FRAME, false)) {
            AbstractQuestContext.SimpleNamedFrame(parent, LinkedList(), AbstractQuestContext.SimpleVarTable(parent),
                "__anon__${System.nanoTime()}", parent.context()).also { it.setNext(action) }
        } else AbstractQuestContext.SimpleActionFrame(parent, LinkedList(), AbstractQuestContext.SimpleVarTable(parent), action, parent.context())
        parent.frames.add(child)
        return child
    }
    @JvmStatic fun <R> runSingle(frame: AbstractQuestContext.AbstractFrame, action: ParsedAction<*>): CompletableFuture<R> {
        check(frame.future == null) { "already running" }
        frame.varTable.initialize(frame)
        val result = action.process(frame)
        frame.future = result
        @Suppress("UNCHECKED_CAST")
        return track(frame.context(), result as CompletableFuture<R>)
    }
    @JvmStatic fun close(frame: AbstractQuestContext.AbstractFrame) {
        frame.frames.toList().forEach { it.close() }
        frame.varTable.close()
        cleanup(frame)
        frame.future?.takeUnless { it.isDone }?.cancel(false)
        frame.future = null
    }
    @JvmStatic fun cleanup(frame: AbstractQuestContext.AbstractFrame) {
        while (frame.closeables.isNotEmpty()) {
            try { frame.closeables.removeFirst().close() } catch (_: Exception) { /* 不替换动作结果。 */ }
        }
    }
    @JvmStatic fun <R> track(context: QuestContext, future: CompletableFuture<R>): CompletableFuture<R> =
        if (context is AbstractQuestContext<*>) context.track(future) else future
    fun unwrap(error: Throwable): Throwable = if (error is CompletionException) error.cause ?: error else error
}

/** 隐藏块游标和同步/异步续接细节；循环处理已完成动作，避免脚本深度占用调用栈。 */
internal class NamedFrameExecution(private val frame: AbstractQuestContext.SimpleNamedFrame, private val name: String) {
    private var block: Quest.Block? = null
    private var nextIndex = -1
    private var currentIndex = -1

    init { frame.context().quest.getBlock(name).ifPresent(::setNext) }
    fun currentAction(): Optional<ParsedAction<*>> =
        if (currentIndex < 0) Optional.empty() else block?.get(currentIndex) ?: Optional.empty()
    fun setNext(action: ParsedAction<*>) {
        val owner = frame.context().quest.blockOf(action).orElseThrow { IllegalArgumentException("$action is not in quest") }
        block = owner
        nextIndex = owner.indexOf(action)
    }
    fun setNext(block: Quest.Block) { this.block = block; nextIndex = 0 }
    fun <R> run(): CompletableFuture<R> {
        check(frame.future == null) { "already running" }
        frame.varTable.initialize(frame)
        val result = CompletableFuture<R>()
        frame.future = result
        process(null, result)
        return FrameExecution.track(frame.context(), result)
    }
    @Suppress("UNCHECKED_CAST")
    private fun <R> process(lastValue: Any?, result: CompletableFuture<R>) {
        var value = lastValue
        val context = frame.context()
        while (!result.isDone && !context.exitStatus.isPresent) {
            if (context is AbstractQuestContext<*> && !context.tryStep()) {
                result.completeExceptionally(context.stepLimitError())
                return
            }
            frame.cleanup()
            frame.frames.removeIf { it.isDone }
            val next = nextAction().orElse(null)
            if (next == null) { result.complete(value as R); return }
            val action = try { next.process(frame) } catch (failure: RuntimeException) {
                result.completeExceptionally(failure)
                return
            }
            if (action.isDone) {
                try { value = action.join() }
                catch (failure: CompletionException) { result.completeExceptionally(failure.cause); return }
                catch (_: CancellationException) { result.cancel(false); return }
            } else {
                val continuation = action.whenCompleteAsync({ completed, failure ->
                    if (failure != null) result.completeExceptionally(FrameExecution.unwrap(failure))
                    else process(completed, result)
                }, context.executor)
                continuation.whenComplete { _, failure ->
                    if (failure != null && !result.isDone) result.completeExceptionally(FrameExecution.unwrap(failure))
                }
                return
            }
        }
        result.complete(value as R)
    }
    private fun nextAction(): Optional<ParsedAction<*>> {
        val currentBlock = block ?: return Optional.empty()
        if (nextIndex < 0) return Optional.empty()
        currentIndex = nextIndex++
        return currentBlock.get(currentIndex)
    }
}

/** 统一分层查找、异步变量解析和局部/父级写入规则。 */
internal class VariableScope(private val parentFrame: QuestContext.Frame?, private val values: MutableMap<String, Any?>) {
    fun parent(): QuestContext.VarTable? = parentFrame?.variables()
    @Suppress("UNCHECKED_CAST")
    fun <R> get(name: String): Optional<R> {
        var value = values[name]
        if (value == null && parentFrame != null) return parentFrame.variables().get(name)
        if (value is QuestFuture<*>) {
            val deferred = value.future
            check(deferred != null && deferred.isDone) {
                "deferred variable is not ready: $name; use getFuture() for asynchronous access"
            }
            value = deferred.join()
        }
        return Optional.ofNullable(value) as Optional<R>
    }
    @Suppress("UNCHECKED_CAST")
    fun <R> getFuture(name: String): Optional<QuestFuture<R>> {
        val value = values[name]
        if (value == null && parentFrame != null) return parentFrame.variables().getFuture(name)
        return Optional.ofNullable(value as? QuestFuture<R>)
    }
    fun set(name: String, value: Any?) {
        val parent = parent()
        if (name.startsWith("~") || parent == null) values[name] = value else parent.set(name, value)
    }
    fun <R> set(name: String, owner: ParsedAction<R>, future: CompletableFuture<R>) { values[name] = QuestFuture(owner, future) }
    fun remove(name: String) { values.remove(name) }
    fun clear() { values.clear() }
    fun keys(): Set<String> = Collections.unmodifiableSet(values.keys)
    fun values(): Collection<MutableMap.MutableEntry<String, Any?>> = Collections.unmodifiableCollection(values.entries)
    fun initialize(frame: QuestContext.Frame) { values.values.forEach { if (it is QuestFuture<*> && it.future == null) it.run(frame) } }
    fun close() { values.values.forEach { if (it is QuestFuture<*> && it.future != null) it.close() } }
}
