/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core

import java.util.Collections
import java.util.Optional
import java.util.function.BiFunction

/** 保持命名空间查找顺序，并隔离注册表的可变集合。 */
@JvmSuppressWildcards
internal class RegistryState {
    private val parsers = linkedMapOf<String?, MutableMap<String?, QuestActionParser?>>()
    private val processors = linkedMapOf<String?, BiFunction<QuestContext.Frame, String, String>?>()
    private var fallbackParser: BiFunction<String, List<String>, QuestActionParser?>? = null

    fun registerAction(namespace: String?, id: String?, parser: QuestActionParser?) {
        parsers.getOrPut(namespace) { linkedMapOf() }[id] = parser
    }
    fun registerStringProcessor(id: String?, processor: BiFunction<QuestContext.Frame, String, String>?) {
        processors[id] = processor
    }
    fun unregisterAction(namespace: String?, id: String?) {
        val actions = parsers[namespace] ?: return
        if (id == "*") actions.clear() else actions.remove(id)
    }
    fun getRegisteredActions(namespace: String?): Collection<String?> =
        parsers[namespace]?.keys?.let { Collections.unmodifiableList(ArrayList(it)) } ?: emptyList()
    fun getRegisteredNamespace(): Collection<String?> = Collections.unmodifiableList(ArrayList(parsers.keys))
    fun getParser(id: String?, namespace: String?): Optional<QuestActionParser> = Optional.ofNullable(parsers[namespace]?.get(id))
    fun getParser(id: String?, namespaces: List<String?>?): Optional<QuestActionParser> {
        val separator = id!!.indexOf(':')
        if (separator > 0 && separator < id.length - 1) return getParser(id.substring(separator + 1), id.substring(0, separator))
        namespaces!!.filterNotNull().forEach { namespace ->
            val parser = getParser(id, namespace)
            if (parser.isPresent) return parser
        }
        @Suppress("UNCHECKED_CAST")
        val snapshot = Collections.unmodifiableList(ArrayList(namespaces)) as List<String>
        return Optional.ofNullable(fallbackParser?.apply(id, snapshot))
    }
    fun getStringProcessor(id: String?): Optional<BiFunction<QuestContext.Frame, String, String>> =
        Optional.ofNullable(processors[id])
    fun setFallbackParser(parser: BiFunction<String, List<String>, QuestActionParser?>?) {
        fallbackParser = parser
    }
}
