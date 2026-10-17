package me.kzheart.klib.script

import me.kzheart.klib.scheduler.SchedulerFactory
import me.kzheart.klib.scheduler.Ticks
import me.kzheart.klib.scope.Scope
import me.kzheart.klib.script.TabooLibKetherInterop.*
import me.kzheart.klib.script.kether.core.ExitStatus
import me.kzheart.klib.script.kether.core.ParsedAction
import me.kzheart.klib.script.kether.core.QuestAction
import me.kzheart.klib.script.kether.core.QuestActionParser
import me.kzheart.klib.script.kether.core.QuestReader
import java.lang.reflect.Array as ReflectArray
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Owns one registration generation: discoveries, replay, import replacement and teardown. */
internal class TabooLibInteropState(
    private val owner: TabooLibKetherInterop,
    private val registry: StatementRegistry,
    providerName: String,
    private val discovery: ContainerDiscovery
) {
    private val providerName = requireText(providerName, "providerName")
    private val listener = registry.listen { entry, _ -> registrationChanged(entry) }
    private val containers = LinkedHashMap<String, OpenContainer>()
    private val imports = LinkedHashMap<String, ImportedRegistration>()
    private val exportKeys = registry.sharedEntries().mapTo(LinkedHashSet()) { key(it.namespace, it.name) }
    private var closed = false

    fun refresh(): Unit = synchronized(owner) {
        if (closed) return@synchronized
        val discovered: List<OpenContainer?>? = discovery.discover()
        val current = LinkedHashMap<String, OpenContainer>()
        for (container in discovered.orEmpty()) {
            if (container == null || container.name() == providerName) continue
            current[container.name()] = container
            val previous = containers[container.name()]
            if (previous !== container) {
                if (previous != null) invalidateImports(container.name())
                registry.sharedEntries().forEach { add(container, it.namespace, it.name) }
            }
        }
        containers.keys.toList().filterNot(current::containsKey).forEach(::invalidateImports)
        containers.clear()
        containers.putAll(current)
    }

    fun parser(action: String, namespaces: List<String>): QuestActionParser {
        val normalized = requireText(action, "action")
        val selected = namespaces.toList()
        return object : QuestActionParser {
            @Suppress("UNCHECKED_CAST") override fun <T> resolve(reader: QuestReader): QuestAction<T> {
                refresh()
                var last: RuntimeException? = null
                for (namespace in selected) for (container in snapshotContainers()) {
                    try {
                        val result = container.call(REMOTE_RESOLVE, providerName, reader, normalized, namespace)
                        if (result.isSuccessful && result.value != null)
                            return TabooLibKetherProtocol.RemoteQuestAction<Any?>(container, providerName, result.value) as QuestAction<T>
                    } catch (failure: RuntimeException) { last = failure }
                }
                throw last ?: UnresolvedActionException(normalized)
            }
        }
    }

    fun resolve(): CompletionStage<Any?> = CompletableFuture<Any?>().apply {
        completeExceptionally(IllegalStateException("TabooLib Kether actions must be resolved during parsing"))
    }

    fun dispose(): Unit = synchronized(owner) {
        if (closed) return@synchronized
        closed = true
        listener.dispose()
        for (entry in registry.sharedEntries()) for (container in containers.values) remove(container, entry.namespace, entry.name)
        imports.values.forEach { it.registration.dispose() }
        imports.clear(); exportKeys.clear(); containers.clear()
        synchronized(TabooLibKetherInterop::class.java) { if (active === owner) active = null }
    }

    fun handle(channel: String?, values: Array<out Any?>?): OpenResult = synchronized(owner) {
        if (closed || channel == null || values == null) return@synchronized OpenResult.failed()
        when (channel) {
            REMOTE_RESOLVE -> {
                requireLength(values, 4, channel)
                val remote = requireContainer(values[0].toString())
                val parser = registry.resolveSharedKether(values[3].toString(), values[2].toString())
                if (!parser.isPresent) return@synchronized OpenResult.failed()
                return@synchronized OpenResult.successful(parser.get().resolve<Any?>(TabooLibKetherProtocol.RemoteQuestReader(remote, providerName, values[1]!!)))
            }
            CREATE_FRAME -> {
                requireLength(values, 2, channel)
                return@synchronized OpenResult.successful(TabooLibKetherProtocol.RemoteFrame(requireContainer(values[0].toString()), providerName, values[1]!!))
            }
            CREATE_PARSED_ACTION -> {
                requireLength(values, 3, channel)
                return@synchronized OpenResult.successful(ParsedAction<Any?>(
                    TabooLibKetherProtocol.RemoteQuestAction<Any?>(requireContainer(values[0].toString()), providerName, values[1]!!),
                    TabooLibKetherProtocol.properties(values[2])))
            }
            CREATE_EXIT_STATUS -> {
                requireLength(values, 3, channel)
                return@synchronized OpenResult.successful(ExitStatus(values[0] as Boolean, values[1] as Boolean, (values[2] as Number).toLong()))
            }
            ADD_ACTION -> {
                requireLength(values, 3, channel)
                val source = values[0].toString()
                val remote = requireContainer(source)
                val namespace = values[2].toString()
                for (action in strings(values[1])) {
                    val key = key(namespace, action)
                    val previous = imports[key]
                    if (previous != null && previous.owner != source) return@synchronized OpenResult.failed()
                    imports.remove(key)?.registration?.dispose()
                    imports[key] = ImportedRegistration(source, registry.registerImportedKether(namespace, action,
                        TabooLibKetherProtocol.RemoteActionParser(remote, providerName, action, namespace)))
                }
                return@synchronized OpenResult.successful()
            }
            REMOVE_ACTION -> {
                require(values.size == 2 || values.size == 3) { "$channel expected 2 or 3 arguments but got ${values.size}" }
                val namespace = values[1].toString()
                val source = if (values.size == 3) values[2].toString() else null
                for (action in strings(values[0])) {
                    val key = key(namespace, action)
                    val registration = imports[key] ?: continue
                    if (if (source != null) registration.owner == source else discovery.find(registration.owner) == null)
                        imports.remove(key)?.registration?.dispose()
                }
                return@synchronized OpenResult.successful()
            }
            else -> return@synchronized OpenResult.failed()
        }
    }

    private fun registrationChanged(entry: StatementRegistry.EntryView): Unit = synchronized(owner) {
        if (closed) return@synchronized
        val changedKey = key(entry.namespace, entry.name)
        if (entry.shared) exportKeys.add(changedKey)
        if (changedKey in exportKeys) {
            val current = registry.resolveSharedKether(entry.namespace, entry.name)
            for (container in containers.values) {
                if (current.isPresent) add(container, entry.namespace, entry.name) else remove(container, entry.namespace, entry.name)
            }
        }
    }
    private fun add(container: OpenContainer, namespace: String, name: String) {
        check(container.call(ADD_ACTION, providerName, arrayOf(name), namespace).isSuccessful) {
            "TabooLib container rejected shared action $namespace:$name in ${container.name()}"
        }
    }
    private fun remove(container: OpenContainer, namespace: String, name: String) {
        if (!container.call(REMOVE_ACTION, arrayOf(name), namespace, providerName).isSuccessful)
            container.call(REMOVE_ACTION, arrayOf(name), namespace)
    }
    private fun snapshotContainers(): List<OpenContainer> = synchronized(owner) { containers.values.toList() }
    private fun requireContainer(name: String): OpenContainer {
        val discovered = discovery.find(name)
        val current = containers[name]
        if (discovered != null && discovered !== current) {
            if (current != null) invalidateImports(name)
            containers[name] = discovered
            return discovered
        }
        return current ?: discovered ?: throw IllegalArgumentException("Unknown TabooLib container $name")
    }
    private fun invalidateImports(owner: String) {
        val removed = imports.filterValues { it.owner == owner }
        removed.values.forEach { it.registration.dispose() }
        removed.keys.forEach(imports::remove)
    }
    private class ImportedRegistration(val owner: String, val registration: StatementRegistration)

    companion object {
        @Volatile private var active: TabooLibKetherInterop? = null
        @JvmStatic fun install(scope: Scope, registry: StatementRegistry, providerName: String, discovery: ContainerDiscovery): TabooLibKetherInterop {
            val interop = TabooLibKetherInterop(registry, providerName, discovery)
            synchronized(TabooLibKetherInterop::class.java) {
                if (active?.state()?.closed == false) {
                    interop.dispose()
                    throw IllegalStateException("TabooLib Kether interop is already installed")
                }
                active = interop
            }
            try {
                scope.install(interop)
                interop.refresh()
                if (scope.findCapability(SchedulerFactory::class.java).isPresent)
                    scope.every(Ticks.seconds(1), Runnable { interop.refresh() })
                return interop
            } catch (failure: RuntimeException) { interop.dispose(); throw failure }
        }
        @JvmStatic fun call(channel: String?, data: Array<out Any?>?): OpenResult = active?.state()?.handle(channel, data) ?: OpenResult.failed()
        private fun key(namespace: String, name: String): String = namespace.lowercase(Locale.ROOT) + ':' + name.lowercase(Locale.ROOT)
        private fun strings(value: Any?): List<String> = when {
            value is Iterable<*> -> value.map { it.toString() }
            value != null && value.javaClass.isArray -> (0 until ReflectArray.getLength(value)).map { ReflectArray.get(value, it).toString() }
            else -> listOf(value.toString())
        }
        private fun requireLength(values: Array<out Any?>, length: Int, channel: String) {
            require(values.size >= length) { "$channel requires $length arguments" }
        }
        private fun requireText(value: String, label: String): String = value.trim { it <= ' ' }.also {
            require(it.isNotEmpty()) { "$label must not be blank" }
        }
    }
}
