package me.kzheart.klib.script

import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope
import me.kzheart.klib.script.kether.core.QuestActionParser as NativeParser
import java.util.ArrayDeque
import java.util.Collections
import java.util.Locale
import java.util.Optional
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** 命名空间、覆盖栈、生命周期回滚和快照版本均封装在注册表内。 */
class StatementRegistry {
    private val lock = ReentrantReadWriteLock()
    private val entries = LinkedHashMap<String, ArrayDeque<Entry>>()
    private var builtinsInstalled = false
    private var version = 0L
    private val listeners = ArrayList<ChangeListener>()

    fun beginBuiltinInstall(): Boolean = lock.write {
        if (builtinsInstalled) false else { builtinsInstalled = true; true }
    }
    fun register(scope: Scope, name: String, parser: QuestActionParser?): StatementRegistration =
        register(scope, DEFAULT_NAMESPACE, name, parser)
    fun register(scope: Scope, namespace: String, name: String, parser: QuestActionParser?): StatementRegistration =
        install(scope, add(normalize(namespace, "namespace"), normalize(name, "name"), parser, null, false, false))
    fun registerKether(scope: Scope, namespace: String, name: String, parser: NativeParser): StatementRegistration =
        registerNative(scope, namespace, name, parser, false)
    fun registerShared(scope: Scope, namespace: String, name: String, parser: NativeParser): StatementRegistration =
        registerNative(scope, namespace, name, parser, true)
    private fun registerNative(scope: Scope, namespace: String, name: String, parser: NativeParser, shared: Boolean): StatementRegistration =
        install(scope, add(normalize(namespace, "namespace"), normalize(name, "name"), null, parser, false, shared))
    private fun install(scope: Scope, entry: Entry): StatementRegistration {
        val registration = Registration(entry)
        try { return scope.install(registration) } catch (failure: RuntimeException) { registration.dispose(); throw failure }
    }
    fun registerImportedKether(namespace: String, name: String, parser: NativeParser): StatementRegistration =
        Registration(add(normalize(namespace, "namespace"), normalize(name, "name"), null, parser, false, false))
    fun registerBuiltin(namespace: String, name: String, parser: QuestActionParser?): StatementRegistration =
        Registration(add(normalize(namespace, "namespace"), normalize(name, "name"), parser, null, true, false))
    fun registerBuiltinKether(name: String, parser: NativeParser): StatementRegistration =
        Registration(add("klib", normalize(name, "name"), null, parser, true, false))

    fun resolve(name: String, namespaces: List<String>): Optional<QuestActionParser> {
        val normalized = normalize(name, "name")
        val separator = normalized.indexOf(':').takeIf { it >= 0 } ?: normalized.indexOf('.')
        if (separator > 0) return resolveExact(normalized.substring(0, separator), normalized.substring(separator + 1))
        for (namespace in namespaces) {
            val parser = resolveExact(namespace, normalized)
            if (parser.isPresent) return parser
        }
        return resolveExact(DEFAULT_NAMESPACE, normalized)
    }
    private fun resolveExact(namespace: String, name: String): Optional<QuestActionParser> {
        val key = key(normalize(namespace, "namespace"), normalize(name, "name"))
        return lock.read { Optional.ofNullable(entries[key]?.peekLast()?.parser) }
    }
    fun registeredNames(): List<String> = snapshot().registeredNames()
    fun version(): Long = lock.read { version }
    fun snapshot(): Snapshot = lock.read {
        val active = entries.filterValues { it.isNotEmpty() }
        Snapshot(version, active.keys.toList(), active.values.map { it.peekLast().view() })
    }
    private fun add(namespace: String, name: String, parser: QuestActionParser?, native: NativeParser?, builtin: Boolean, shared: Boolean): Entry {
        require((parser == null) != (native == null)) { "exactly one of parser and ketherParser must be provided" }
        val entry = Entry(namespace, name, parser, native, builtin, shared)
        lock.write {
            val stack = entries.getOrPut(key(namespace, name)) { ArrayDeque() }
            if (builtin) {
                stack.firstOrNull { it.builtin }?.let { return it }
                stack.addFirst(entry)
            } else stack.addLast(entry)
            version++
        }
        notifyListeners(entry, true)
        return entry
    }
    private fun remove(entry: Entry) {
        val changed = lock.write {
            val key = key(entry.namespace, entry.name)
            val stack = entries[key]
            if (stack != null && stack.remove(entry)) {
                if (stack.isEmpty()) entries.remove(key)
                version++
                true
            } else false
        }
        if (changed) notifyListeners(entry, false)
    }
    fun listen(listener: ChangeListener): ListenerRegistration = lock.write {
        listeners.add(listener)
        ListenerRegistration(listener)
    }
    fun sharedEntries(): List<EntryView> = lock.read {
        entries.values.mapNotNull { it.peekLast() }.filter { it.shared && it.ketherParser != null }.map { it.view() }
    }
    fun resolveSharedKether(namespace: String, name: String): Optional<NativeParser> = lock.read {
        val entry = entries[key(normalize(namespace, "namespace"), normalize(name, "name"))]?.peekLast()
        Optional.ofNullable(entry?.takeIf { it.shared }?.ketherParser)
    }
    private fun notifyListeners(entry: Entry, added: Boolean) {
        val view = entry.view()
        val snapshot = lock.read { listeners.toList() }
        snapshot.forEach { it.changed(view, added) }
    }
    private class Entry(val namespace: String, val name: String, val parser: QuestActionParser?,
        val ketherParser: NativeParser?, val builtin: Boolean, val shared: Boolean) {
        fun view(): EntryView = EntryView(namespace, name, parser, ketherParser, shared)
    }
    class EntryView(@JvmField val namespace: String, @JvmField val name: String,
        @JvmField val parser: QuestActionParser?, @JvmField val ketherParser: NativeParser?, @JvmField val shared: Boolean)
    fun interface ChangeListener { fun changed(entry: EntryView, added: Boolean) }
    inner class ListenerRegistration internal constructor(private val listener: ChangeListener) : Disposable {
        private var active = true
        override fun dispose() { lock.write { if (active) { active = false; listeners.remove(listener) } } }
    }
    class Snapshot internal constructor(private val version: Long, names: List<String>, entries: List<EntryView>) {
        private val registeredNames = Collections.unmodifiableList(names)
        private val entries = Collections.unmodifiableList(entries)
        fun version(): Long = version
        fun registeredNames(): List<String> = registeredNames
        fun entries(): List<EntryView> = entries
    }
    private inner class Registration(private val entry: Entry) : StatementRegistration {
        private var registered = true
        override fun namespace(): String = entry.namespace
        override fun name(): String = entry.name
        @Synchronized override fun isRegistered(): Boolean = registered
        override fun dispose() {
            synchronized(this) {
                if (!registered) return
                registered = false
            }
            remove(entry)
        }
    }
    companion object {
        private const val DEFAULT_NAMESPACE = "global"
        private fun key(namespace: String, name: String): String = "$namespace:$name"
        private fun normalize(value: String, label: String): String = value.trim { it <= ' ' }.lowercase(Locale.ROOT).also {
            require(it.isNotEmpty()) { "$label must not be blank" }
        }
    }
}
