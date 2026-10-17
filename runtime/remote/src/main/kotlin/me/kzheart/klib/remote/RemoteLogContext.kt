package me.kzheart.klib.remote

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Collections

/** 一条日志的有界 context、MDC 和标签。 */
class RemoteLogContext private constructor(builder: Builder) {
    private val context = Collections.unmodifiableMap(LinkedHashMap(builder.contextValues))
    private val mdc = Collections.unmodifiableMap(LinkedHashMap(builder.mdcValues))
    private val tags = Collections.unmodifiableList(ArrayList(builder.tagValues))
    fun context(): Map<String, String> = context
    fun mdc(): Map<String, String> = mdc
    fun tags(): List<String> = tags
    class Builder {
        internal val contextValues = LinkedHashMap<String, String>()
        internal val mdcValues = LinkedHashMap<String, String>()
        internal val tagValues = ArrayList<String>()
        fun context(key: String, value: Any?): Builder = apply { put(contextValues, key, value, "context") }
        fun context(values: Map<String, *>): Builder = apply { values.forEach { (key, value) -> context(key, value) } }
        fun mdc(key: String, value: Any?): Builder = apply { put(mdcValues, key, value, "mdc") }
        fun tag(value: String): Builder = apply {
            val tag = Texts.requireText(value, "tag")
            check(tagValues.size < 16) { "tags exceed limit" }
            requireBytes(tag, 64, "tag")
            tagValues.add(tag)
        }
        fun build(): RemoteLogContext = RemoteLogContext(this)
        private fun put(target: MutableMap<String, String>, key: String, value: Any?, kind: String) {
            val normalizedKey = Texts.requireText(key, "$kind key")
            val normalizedValue = value.toString()
            check(target.containsKey(normalizedKey) || target.size < 32) { "$kind fields exceed limit" }
            requireBytes(normalizedKey, 64, "$kind key")
            requireBytes(normalizedValue, 1024, "$kind value")
            target[normalizedKey] = normalizedValue
        }
        private fun requireBytes(value: String, limit: Int, name: String) {
            require(value.toByteArray(UTF_8).size <= limit) { "$name exceeds byte limit" }
        }
    }
    companion object { @JvmStatic fun builder(): Builder = Builder() }
}
