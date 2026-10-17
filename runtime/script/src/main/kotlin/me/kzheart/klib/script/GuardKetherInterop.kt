package me.kzheart.klib.script

import me.kzheart.klib.guard.PluginHost
import me.kzheart.klib.guard.kether.KetherInteropEndpoint
import me.kzheart.klib.guard.kether.KetherInteropPeer
import me.kzheart.klib.guard.kether.KetherInteropProtocol
import me.kzheart.klib.guard.kether.KetherInteropRegistration
import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope
import me.kzheart.klib.script.TabooLibKetherInterop.OpenContainer
import me.kzheart.klib.script.TabooLibKetherInterop.OpenResult
import me.kzheart.klib.script.kether.core.QuestAction
import me.kzheart.klib.script.kether.core.QuestActionParser
import me.kzheart.klib.script.kether.core.QuestReader
import java.lang.reflect.Array as ReflectArray
import java.math.BigDecimal
import java.math.BigInteger
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicLong

/** Keeps generation ownership, opaque action handles and cancellation inside one lifecycle. */
class GuardKetherInterop private constructor(private val registry: StatementRegistry, productId: String) :
    UnknownStatementResolver, KetherParserResolver, KetherInteropEndpoint, Disposable {
    private val productId = requireText(productId, "productId")
    private val listener = registry.listen { entry, _ -> registrationChanged(entry) }
    private val exportKeys = registry.sharedEntries().mapTo(LinkedHashSet()) { key(it.namespace, it.name) }
    private val imports = LinkedHashMap<String, ImportedRegistration>()
    private val actions = LinkedHashMap<Long, QuestAction<*>>()
    private val running = LinkedHashMap<CompletableFuture<*>, CompletableFuture<Any?>>()
    private val nextHandle = AtomicLong()
    private var registration: KetherInteropRegistration? = null
    private var active = true

    @Synchronized override fun addActions(peer: KetherInteropPeer, namespace: String, actionNames: Array<String>) {
        ensureActive()
        val normalizedNamespace = requireText(namespace, "namespace")
        val container = PeerContainer(peer)
        for (action in requireActions(actionNames)) {
            val importedKey = key(normalizedNamespace, action)
            val previous = imports[importedKey]
            check(previous == null || previous.owner == peer.name()) { "External Kether action conflict: $normalizedNamespace:$action" }
            previous?.registration?.dispose()
            val parser = TabooLibKetherProtocol.RemoteActionParser(container, KetherInteropProtocol.PROVIDER_NAME, action, normalizedNamespace)
            imports[importedKey] = ImportedRegistration(peer.name(), registry.registerImportedKether(normalizedNamespace, action, parser), parser)
        }
    }
    @Synchronized override fun removeActions(peer: KetherInteropPeer, namespace: String, actionNames: Array<String>) {
        if (!active) return
        val normalizedNamespace = requireText(namespace, "namespace")
        for (action in requireActions(actionNames)) {
            val importedKey = key(normalizedNamespace, action)
            if (imports[importedKey]?.owner == peer.name()) imports.remove(importedKey)?.registration?.dispose()
        }
    }
    @Synchronized override fun resolve(peer: KetherInteropPeer, consumerName: String, reader: Any, action: String, namespace: String): Long {
        ensureActive()
        requireText(consumerName, "consumerName")
        val parser = registry.resolveSharedKether(namespace, action).orElseThrow { IllegalArgumentException("No shared Kether action: $namespace:$action") }
        val resolved = parser.resolve<Any?>(TabooLibKetherProtocol.RemoteQuestReader(PeerContainer(peer), KetherInteropProtocol.PROVIDER_NAME, reader))
        val handle = nextHandle.incrementAndGet()
        check(handle > 0) { "Kether action handle space exhausted" }
        actions[handle] = resolved
        return handle
    }
    @Suppress("UNCHECKED_CAST")
    @Synchronized override fun process(peer: KetherInteropPeer, consumerName: String, actionHandle: Long, frame: Any): CompletionStage<Any?> {
        if (!active) return failed(stale())
        requireText(consumerName, "consumerName")
        val action = actions[actionHandle] as QuestAction<Any?>? ?: return failed(IllegalStateException("Unknown or stale Kether action handle: $actionHandle"))
        val source: CompletableFuture<Any?>? = try {
            action.process(TabooLibKetherProtocol.RemoteFrame(PeerContainer(peer), KetherInteropProtocol.PROVIDER_NAME, frame))
        } catch (failure: RuntimeException) { return failed(failure) }
        if (source == null) return failed(IllegalStateException("Kether action returned null future"))
        val result = CompletableFuture<Any?>()
        running[source] = result
        source.whenComplete { value, failure -> complete(source, result, value, failure) }
        return result
    }
    @Synchronized override fun release(actionHandle: Long) { actions.remove(actionHandle) }
    override fun parser(action: String, namespaces: List<String>): QuestActionParser {
        val normalizedAction = requireText(action, "action")
        val selected = namespaces.toList()
        return object : QuestActionParser {
            override fun <T> resolve(reader: QuestReader): QuestAction<T> {
                synchronized(this@GuardKetherInterop) {
                    ensureActive()
                    for (namespace in selected) {
                        val imported = imports[key(namespace, normalizedAction)] ?: continue
                        if (imported.registration.isRegistered && !registry.resolveSharedKether(namespace, normalizedAction).isPresent)
                            return imported.parser.resolve(reader)
                    }
                }
                throw IllegalArgumentException("No external Kether action resolved: $normalizedAction")
            }
        }
    }
    override fun resolve(statement: String?, context: ScriptContext?): CompletionStage<Any?> =
        failed(IllegalStateException("Guard Kether actions must be resolved during parsing"))

    @Synchronized override fun dispose() {
        if (!active) return
        active = false
        listener.dispose()
        val current = registration
        registration = null
        current?.close()
        imports.values.forEach { it.registration.dispose() }
        imports.clear(); exportKeys.clear(); actions.clear()
        val stale = stale()
        val pending = running.toList()
        running.clear()
        for ((source, result) in pending) { result.completeExceptionally(stale); source.cancel(true) }
    }
    override fun close() = dispose()
    @Synchronized private fun registrationChanged(entry: StatementRegistry.EntryView) {
        if (!active) return
        val current = registration ?: return
        val exportKey = key(entry.namespace, entry.name)
        if (entry.shared) exportKeys.add(exportKey)
        if (exportKey !in exportKeys) return
        if (registry.resolveSharedKether(entry.namespace, entry.name).isPresent) current.publish(entry.namespace, entry.name)
        else current.withdraw(entry.namespace, entry.name)
    }
    @Synchronized private fun publishAll() {
        ensureActive()
        for (entry in registry.sharedEntries()) registration!!.publish(entry.namespace, entry.name)
    }
    @Synchronized private fun complete(source: CompletableFuture<*>, result: CompletableFuture<Any?>, value: Any?, failure: Throwable?) {
        running.remove(source)
        when {
            !active -> result.completeExceptionally(stale())
            failure != null -> result.completeExceptionally(failure)
            else -> try { result.complete(GuardValueBoundary(javaClass.classLoader).copy(value)) }
                catch (invalid: RuntimeException) { result.completeExceptionally(invalid) }
        }
    }
    private fun ensureActive() { if (!active) throw stale() }
    private fun stale() = IllegalStateException("Kether product generation is no longer active: $productId")
    private class ImportedRegistration(val owner: String, val registration: StatementRegistration, val parser: QuestActionParser)
    private class PeerContainer(private val peer: KetherInteropPeer) : OpenContainer {
        override fun name(): String = peer.name()
        override fun call(channel: String?, vararg data: Any?): OpenResult {
            val result = peer.call(channel, *data)
            return if (result != null && result.isSuccessful) OpenResult.successful(result.value) else OpenResult.failed()
        }
    }
    companion object {
        @JvmStatic fun install(scope: Scope, registry: StatementRegistry, host: PluginHost): GuardKetherInterop {
            val interop = GuardKetherInterop(registry, host.productId())
            try {
                interop.registration = host.ketherInteropBroker().attach(host.productId(), host.generation(), registry.javaClass.classLoader, interop)
                scope.install(interop)
                interop.publishAll()
                return interop
            } catch (failure: RuntimeException) { interop.dispose(); throw failure }
        }
        private fun requireActions(values: Array<String>): List<String> = values.map { requireText(it, "action") }.also {
            require(it.isNotEmpty()) { "actions must not be empty" }
        }
        private fun key(namespace: String, action: String): String = requireText(namespace, "namespace").lowercase(Locale.ROOT) + ':' + requireText(action, "action").lowercase(Locale.ROOT)
        private fun requireText(value: String, label: String): String = value.trim { it <= ' ' }.also { require(it.isNotEmpty()) { "$label must not be blank" } }
        private fun failed(failure: Throwable): CompletionStage<Any?> = CompletableFuture<Any?>().apply { completeExceptionally(failure) }
    }
}

/** Copies only the supported cross-loader value graph and enforces its depth/cycle limits. */
private class GuardValueBoundary(private val productLoader: ClassLoader?) {
    private val visited = IdentityHashMap<Any, Boolean>()
    fun copy(value: Any?, depth: Int = 0): Any? {
        if (value == null) return null
        val type = value.javaClass
        if (type in SCALARS) return value
        require(depth < 16 && visited.size < 256) { "Kether result exceeds the Guard value boundary" }
        require(type.classLoader !== productLoader) { "Kether result contains a product-private value: ${type.name}" }
        require(visited.put(value, true) == null) { "Kether result contains a cyclic value graph" }
        try {
            return when {
                value is Map<*, *> -> LinkedHashMap<Any?, Any?>().apply { value.forEach { (k, v) -> put(copy(k, depth + 1), copy(v, depth + 1)) } }
                value is Collection<*> -> value.mapTo(ArrayList()) { copy(it, depth + 1) }
                type.isArray -> {
                    val length = ReflectArray.getLength(value)
                    if (type.componentType.isPrimitive) ReflectArray.newInstance(type.componentType, length).also { System.arraycopy(value, 0, it, 0, length) }
                    else Array<Any?>(length) { copy(ReflectArray.get(value, it), depth + 1) }
                }
                else -> value
            }
        } finally { visited.remove(value) }
    }
    companion object {
        private val SCALARS = setOf(String::class.java, Boolean::class.javaObjectType, Byte::class.javaObjectType,
            Short::class.javaObjectType, Int::class.javaObjectType, Long::class.javaObjectType, Float::class.javaObjectType,
            Double::class.javaObjectType, Char::class.javaObjectType, BigInteger::class.java, BigDecimal::class.java)
    }
}
