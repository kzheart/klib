package me.kzheart.klib.script

import me.kzheart.klib.script.kether.core.QuestContext
import java.util.Collections
import java.util.Locale
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/** 宿主服务不可变共享，变量视图按帧绑定；显式 null 与缺失变量分别处理。 */
class ScriptContext private constructor(
    private val fixedSender: Any?, private val senderVariable: String?,
    private val variables: ConcurrentHashMap<String, Any>, private val frame: QuestContext.Frame?,
    namespaces: List<String>, private val locale: Locale, private val services: Map<Class<*>, Any>
) {
    private val namespaces: List<String> = Collections.unmodifiableList(ArrayList(namespaces))
    fun atFrame(selectedFrame: QuestContext.Frame): ScriptContext =
        ScriptContext(fixedSender, senderVariable, variables, selectedFrame, namespaces, locale, services)
    fun sender(): Optional<Any> {
        frame?.context()?.rootFrame()?.variables()?.getOrNull<Any>(SENDER_OVERRIDE)?.let { return Optional.of(it) }
        return Optional.ofNullable(if (senderVariable == null) fixedSender else variableOrNull(senderVariable))
    }
    fun variable(name: String): Optional<Any> = Optional.ofNullable(variableOrNull(name))
    fun variableOrNull(name: String): Any? {
        val key = requireName(name)
        if (frame != null) return if (ScriptFrames.isInternal(key)) null else frame.variables().getOrNull<Any>(key)
        return variables[key].takeUnless { it === NULL_VALUE }
    }
    fun setVariable(name: String, value: Any?) {
        val normalized = requireName(name)
        if (value == null) removeVariable(normalized) else writeVariable(normalized, value)
    }
    fun removeVariable(name: String): Any? {
        val key = requireName(name)
        if (frame != null) {
            ScriptFrames.checkWritable(key)
            var table: QuestContext.VarTable? = frame.variables()
            while (table != null) {
                if (key in table.keys()) {
                    val previous = table.toMap()[key]
                    table.remove(key)
                    return previous
                }
                table = table.parent()
            }
            return null
        }
        return variables.remove(key).takeUnless { it === NULL_VALUE }
    }
    fun variables(): Map<String, Any?> {
        if (frame != null) return ScriptFrames.variables(frame)
        return Collections.unmodifiableMap(variables.mapValuesTo(LinkedHashMap()) { (_, value) -> value.takeUnless { it === NULL_VALUE } })
    }
    fun writeVariable(name: String, value: Any?) {
        if (frame == null) variables[name] = value ?: NULL_VALUE
        else { ScriptFrames.checkWritable(name); frame.variables().set(name, value) }
    }
    fun namespaces(): List<String> = namespaces
    fun withNamespaces(vararg values: String): ScriptContext {
        val selected = values.mapTo(ArrayList()) { requireName(it) }
        namespaces.forEach { if (it !in selected) selected.add(it) }
        return ScriptContext(fixedSender, senderVariable, variables, frame, selected, locale, services)
    }
    fun locale(): Locale = locale
    @Suppress("UNCHECKED_CAST")
    fun <T> service(type: Class<T>): Optional<T> = Optional.ofNullable(services[type]?.let { type.cast(it) }) as Optional<T>
    fun <T> requireService(type: Class<T>): T = service(type).orElseThrow {
        IllegalStateException("Script service is not installed: ${type.name}")
    }

    class Builder internal constructor() {
        private var sender: Any? = null
        private var senderVariable: String? = null
        private val variables = LinkedHashMap<String, Any?>()
        private val namespaces = arrayListOf("klib", "global")
        private var locale = Locale.SIMPLIFIED_CHINESE
        private val services = LinkedHashMap<Class<*>, Any>()
        fun sender(value: Any?): Builder = apply { sender = value }
        fun senderVariable(name: String): Builder = apply { senderVariable = requireName(name) }
        fun variable(name: String, value: Any?): Builder = apply { variables[requireName(name)] = value }
        fun variables(values: Map<String, *>): Builder = apply { values.forEach { (key, value) -> variable(key, value) } }
        fun namespaces(vararg values: String): Builder = apply {
            namespaces.clear()
            values.forEach { namespaces.add(requireName(it)) }
            if ("klib" !in namespaces) namespaces.add("klib")
            if ("global" !in namespaces) namespaces.add("global")
        }
        fun locale(value: Locale): Builder = apply { locale = value }
        fun <T> service(type: Class<T>, service: T): Builder = apply {
            if (service == null) throw NullPointerException("service")
            require(type.isInstance(service)) { "Service does not implement ${type.name}" }
            services[type] = service
        }
        fun build(): ScriptContext = ScriptContext(sender, senderVariable,
            ConcurrentHashMap<String, Any>().apply { variables.forEach { (key, value) -> put(key, value ?: NULL_VALUE) } },
            null, namespaces, locale, Collections.unmodifiableMap(LinkedHashMap(services)))
    }
    companion object {
        private val NULL_VALUE = Any()
        const val SENDER_OVERRIDE = "~klib:sender"
        @JvmStatic fun builder(): Builder = Builder()
        private fun requireName(name: String): String = name.trim { it <= ' ' }.also {
            require(it.isNotEmpty()) { "Variable name must not be blank" }
        }
    }
}
