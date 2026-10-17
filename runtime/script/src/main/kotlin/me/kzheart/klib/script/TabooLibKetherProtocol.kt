package me.kzheart.klib.script

import me.kzheart.klib.script.TabooLibKetherInterop.OpenContainer
import me.kzheart.klib.script.TabooLibKetherInterop.*
import me.kzheart.klib.script.kether.core.ExitStatus
import me.kzheart.klib.script.kether.core.ParsedAction
import me.kzheart.klib.script.kether.core.Quest
import me.kzheart.klib.script.kether.core.QuestAction
import me.kzheart.klib.script.kether.core.QuestActionParser
import me.kzheart.klib.script.kether.core.QuestContext
import me.kzheart.klib.script.kether.core.QuestFuture
import me.kzheart.klib.script.kether.core.QuestReader
import me.kzheart.klib.script.kether.core.QuestService
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import me.kzheart.klib.script.ReflectiveTabooLibContainers.Companion.invoke
import me.kzheart.klib.script.ReflectiveTabooLibContainers.Companion.property

/** Cross-loader shape conversion is centralized in Peer; frames and readers remain thin views. */
class TabooLibKetherProtocol private constructor() {
    class RemoteActionParser(private val remote: OpenContainer, private val consumerName: String,
        private val action: String, private val namespace: String) : QuestActionParser {
        override fun <T> resolve(reader: QuestReader): QuestAction<T> {
            val result = remote.call(REMOTE_RESOLVE, consumerName, reader, action, namespace)
            check(result.isSuccessful && result.value != null) { "Unable to create remote action $namespace:$action" }
            return RemoteQuestAction(remote, consumerName, result.value)
        }
    }
    class RemoteQuestReader(remote: OpenContainer, consumerName: String, private val source: Any) : QuestReader {
        private val peer = Peer(remote, consumerName)
        override fun peek(): Char = invoke(source, "peek") as Char
        override fun peek(offset: Int): Char = invoke(source, "peek", offset) as Char
        override fun getIndex(): Int = (invoke(source, "getIndex") as Number).toInt()
        override fun setIndex(index: Int) {
            try { invoke(source, "setIndex", index) }
            catch (_: IllegalStateException) { ReflectiveTabooLibContainers.setProperty(source, "index", index) }
        }
        override fun getMark(): Int = (invoke(source, "getMark") as Number).toInt()
        override fun hasNext(): Boolean = invoke(source, "hasNext") as Boolean
        override fun nextToken(): String = invoke(source, "nextToken").toString()
        override fun mark() { invoke(source, "mark") }
        override fun reset() { invoke(source, "reset") }
        override fun expect(value: String) { invoke(source, "expect", value) }
        override fun hasLineBreakBeforeNextToken(): Boolean = try { invoke(source, "hasLineBreakBeforeNextToken") as Boolean } catch (_: IllegalStateException) { false }
        override fun <T> nextAction(): ParsedAction<T> = nextAction(null)
        @Suppress("UNCHECKED_CAST") override fun <T> nextAction(namespace: String?): ParsedAction<T> = peer.fromParsed(
            if (namespace == null) invoke(source, "nextAction")!! else invoke(source, "nextAction", namespace)!!) as ParsedAction<T>
    }
    class RemoteQuestAction<T>(private val remote: OpenContainer, private val consumerName: String, private val source: Any) : QuestAction<T>() {
        @Suppress("UNCHECKED_CAST") override fun process(frame: QuestContext.Frame): CompletableFuture<T> {
            val result = remote.call(CREATE_FRAME, consumerName, LocalFrameSource(remote, consumerName, frame))
            if (!result.isSuccessful || result.value == null) return CompletableFuture<T>().apply {
                completeExceptionally(IllegalStateException("Unable to create remote Kether frame in ${remote.name()}"))
            }
            val future = invoke(source, "process", result.value)
            check(future is CompletableFuture<*>) { "Remote Kether action did not return CompletableFuture" }
            return future as CompletableFuture<T>
        }
    }
    /** Stable reflection shape, including the older container's wrapped exit-status form. */
    class LocalFrameSource(private val remote: OpenContainer, private val consumerName: String, private val delegate: QuestContext.Frame) {
        private val peer = Peer(remote, consumerName)
        fun name(): String = delegate.name()
        fun context(): Any = LocalContextSource(remote, consumerName, delegate.context())
        fun currentAction(): Optional<ParsedAction<*>> = delegate.currentAction()
        fun children(): List<Any> = delegate.children().mapTo(ArrayList()) { LocalFrameSource(remote, consumerName, it) }
        fun parent(): Optional<Any> = delegate.parent().map { LocalFrameSource(remote, consumerName, it) }
        fun setNext(value: Any) {
            if (value is ParsedAction<*>) { delegate.setNext(value); return }
            try { delegate.setNext(peer.fromParsed(value)) }
            catch (notAction: RuntimeException) {
                val block = delegate.context().quest.getBlock(property(value, "label").toString())
                if (!block.isPresent) throw notAction
                delegate.setNext(block.get())
            }
        }
        fun newFrame(name: String): Any = LocalFrameSource(remote, consumerName, delegate.newFrame(name))
        fun newFrame(action: Any): Any = LocalFrameSource(remote, consumerName, delegate.newFrame(if (action is ParsedAction<*>) action else peer.fromParsed(action)))
        fun variables(): Any = delegate.variables()
        fun <C : AutoCloseable?> addClosable(closeable: C): C = delegate.addClosable(closeable)
        fun <T> run(): CompletableFuture<T> = delegate.run()
        fun close() = delegate.close()
        fun isDone(): Boolean = delegate.isDone
    }
    class LocalContextSource(private val remote: OpenContainer, private val consumerName: String, private val delegate: QuestContext) {
        fun getQuest(): Any = delegate.quest
        fun getExitStatus(): Optional<ExitStatus> = delegate.exitStatus
        fun runActions(): CompletableFuture<Any?> = delegate.runActions()
        fun getExecutor(): Executor = delegate.executor
        fun terminate() = delegate.terminate()
        fun rootFrame(): Any = LocalFrameSource(remote, consumerName, delegate.rootFrame())
        fun setExitStatus(foreign: Any) {
            var value: Any = foreign
            try { if (property(foreign, "successful") == true) value = property(foreign, "value")!! }
            catch (_: IllegalStateException) { /* Newer containers pass ExitStatus directly. */ }
            delegate.setExitStatus(if (value is ExitStatus) value else exitStatus(value))
        }
    }
    class RemoteFrame(remote: OpenContainer, consumerName: String, private val source: Any) : QuestContext.Frame {
        private val peer = Peer(remote, consumerName)
        override fun name(): String = invoke(source, "name").toString()
        override fun context(): QuestContext = RemoteContext(peer, invoke(source, "context")!!)
        override fun currentAction(): Optional<ParsedAction<*>> = foreignOptional(invoke(source, "currentAction"), peer::fromParsed)
        override fun children(): List<QuestContext.Frame> = (invoke(source, "children") as List<*>).mapTo(ArrayList()) { peer.frame(it!!) }
        override fun parent(): Optional<QuestContext.Frame> = foreignOptional(invoke(source, "parent"), peer::frame)
        override fun setNext(action: ParsedAction<*>) { invoke(source, "setNext", peer.toParsed(action)) }
        override fun setNext(block: Quest.Block) {
            require(block is RemoteBlock) { "Kether block belongs to a different runtime" }
            invoke(source, "setNext", block.source)
        }
        override fun newFrame(name: String): QuestContext.Frame = peer.frame(invoke(source, "newFrame", name)!!)
        override fun newFrame(action: ParsedAction<*>): QuestContext.Frame = peer.frame(invoke(source, "newFrame", peer.toParsed(action))!!)
        override fun variables(): QuestContext.VarTable = RemoteVarTable(peer, invoke(source, "variables")!!)
        @Suppress("UNCHECKED_CAST") override fun <C : AutoCloseable?> addClosable(closeable: C): C = invoke(source, "addClosable", closeable) as C
        @Suppress("UNCHECKED_CAST") override fun <T> run(): CompletableFuture<T> = invoke(source, "run") as CompletableFuture<T>
        override fun close() { invoke(source, "close") }
        override fun isDone(): Boolean = invoke(source, "isDone") as Boolean
    }
    private class RemoteContext(private val peer: Peer, private val source: Any) : QuestContext {
        override fun getService(): QuestService<out QuestContext> = throw UnsupportedOperationException("remote Kether context has no local service")
        override fun getQuest(): Quest = RemoteQuest(peer, invoke(source, "getQuest")!!)
        override fun setExitStatus(status: ExitStatus) {
            val result = peer.remote.call(CREATE_EXIT_STATUS, status.isRunning, status.isWaiting, status.startTime)
            check(result.isSuccessful) { "Unable to create remote exit status" }
            invoke(source, "setExitStatus", result.value)
        }
        override fun getExitStatus(): Optional<ExitStatus> = foreignOptional(invoke(source, "getExitStatus"), ::exitStatus)
        @Suppress("UNCHECKED_CAST") override fun runActions(): CompletableFuture<Any?> = invoke(source, "runActions") as CompletableFuture<Any?>
        override fun getExecutor(): Executor {
            val executor = invoke(source, "getExecutor")!!
            return executor as? Executor ?: Executor { invoke(executor, "execute", it) }
        }
        override fun terminate() { invoke(source, "terminate") }
        override fun rootFrame(): QuestContext.Frame = peer.frame(invoke(source, "rootFrame")!!)
    }
    private class RemoteVarTable(private val peer: Peer, private val source: Any) : ForeignVarTableEntries() {
        @Suppress("UNCHECKED_CAST") override fun <T> get(name: String): Optional<T> =
            (invoke(source, "get", name) as? Optional<T>) ?: (Optional.empty<Any>() as Optional<T>)
        @Suppress("UNCHECKED_CAST") override fun <T> getFuture(name: String): Optional<QuestFuture<T>> = foreignOptional(invoke(source, "getFuture", name)) { foreign ->
            if (foreign is QuestFuture<*>) foreign as QuestFuture<T>
            else QuestFuture(peer.fromParsed(property(foreign, "action")!!) as ParsedAction<T>, property(foreign, "future") as? CompletableFuture<T>)
        }
        override fun set(name: String, value: Any?) { invoke(source, "set", name, value) }
        override fun remove(name: String) { invoke(source, "remove", name) }
        override fun clear() { invoke(source, "clear") }
        override fun <T> set(name: String, owner: ParsedAction<T>, future: CompletableFuture<T>?) { invoke(source, "set", name, peer.toParsed(owner), future) }
        @Suppress("UNCHECKED_CAST") override fun keys(): Set<String> = invoke(source, "keys") as Set<String>
        override fun rawEntries(): Any? = invoke(source, "values")
        override fun initialize(frame: QuestContext.Frame) {
            val result = peer.remote.call(CREATE_FRAME, peer.consumerName, frame)
            invoke(source, "initialize", result.value)
        }
        override fun close() { invoke(source, "close") }
        override fun parent(): QuestContext.VarTable? = invoke(source, "parent")?.let { RemoteVarTable(peer, it) }
    }
    private class RemoteQuest(private val peer: Peer, private val source: Any) : Quest {
        override fun getId(): String = invoke(source, "getId").toString()
        override fun getBlock(label: String): Optional<Quest.Block> = foreignOptional(invoke(source, "getBlock", label), peer::block)
        override fun getBlocks(): Map<String, Quest.Block> = (invoke(source, "getBlocks") as Map<*, *>).entries.associateTo(LinkedHashMap()) { it.key as String to peer.block(it.value!!) }
        override fun blockOf(action: ParsedAction<*>): Optional<Quest.Block> = foreignOptional(invoke(source, "blockOf", peer.toParsed(action)), peer::block)
    }
    private class RemoteBlock(private val peer: Peer, val source: Any) : Quest.Block {
        override fun getLabel(): String = invoke(source, "getLabel").toString()
        override fun getActions(): List<ParsedAction<*>> = (invoke(source, "getActions") as List<*>).mapTo(ArrayList()) { peer.fromParsed(it!!) }
        override fun indexOf(action: ParsedAction<*>): Int = (invoke(source, "indexOf", peer.toParsed(action)) as Number).toInt()
        override fun get(index: Int): Optional<ParsedAction<*>> = foreignOptional(invoke(source, "get", index), peer::fromParsed)
    }
    private class Peer(val remote: OpenContainer, val consumerName: String) {
        fun frame(source: Any): QuestContext.Frame = RemoteFrame(remote, consumerName, source)
        fun block(source: Any): Quest.Block = RemoteBlock(this, source)
        fun fromParsed(source: Any): ParsedAction<*> = ParsedAction<Any?>(
            RemoteQuestAction(remote, consumerName, property(source, "action")!!), properties(property(source, "properties")))
        fun toParsed(action: ParsedAction<*>): Any {
            val result = remote.call(CREATE_PARSED_ACTION, consumerName, action.action, action.properties)
            check(result.isSuccessful && result.value != null) { "Unable to create remote ParsedAction in ${remote.name()}" }
            return result.value
        }
    }
    companion object {
        @Suppress("UNCHECKED_CAST") @JvmStatic fun properties(source: Any?): MutableMap<String, Any?> = source as MutableMap<String, Any?>
        private fun exitStatus(value: Any): ExitStatus = ExitStatus(property(value, "running") as Boolean, property(value, "waiting") as Boolean, (property(value, "startTime") as Number).toLong())
        private fun <T : Any> foreignOptional(source: Any?, convert: (Any) -> T): Optional<T> =
            if (source is Optional<*> && source.isPresent) Optional.of(convert(source.get())) else Optional.empty()
    }
}
