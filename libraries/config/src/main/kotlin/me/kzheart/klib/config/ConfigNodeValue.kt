package me.kzheart.klib.config

import java.util.Collections
import java.util.Locale
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.error.Mark
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.NodeTuple
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag

/** A path-aware view of a YAML syntax node or a parsed value tree. */
internal class ConfigNodeValue private constructor(
    private val document: YamlDocument?,
    private val node: Node?,
    private val path: String,
    private val name: String,
    private val valueDocument: ValueDocument?,
    private val value: Any?,
    private val valueExists: Boolean,
    private val valueParent: Any?,
    private val valueKey: Any?,
) {
    private var keyMark: Mark? = null
    constructor(document: YamlDocument, node: Node?, path: String) :
        this(document, node, path, path.substringAfterLast('.'), null, null, false, null, null)
    private constructor(document: YamlDocument, node: Node?, path: String, name: String) :
        this(document, node, path, name, null, null, false, null, null)
    constructor(document: ValueDocument, value: Any?, exists: Boolean, path: String, name: String) :
        this(null, null, path, name, document, value, exists, null, null)
    private constructor(document: ValueDocument, value: Any?, exists: Boolean, path: String, name: String, parent: Any?, key: Any?) :
        this(null, null, path, name, document, value, exists, parent, key)

    fun sourceName(): String = valueDocument?.sourceName() ?: requireNotNull(document).sourceName()
    fun path(): String = path
    fun name(): String = name
    fun sectionText(owner: ConfigNode): String {
        if (!isMapping) throw mappingError("expected a mapping to render a section")
        return valueDocument?.sectionText(owner, value) ?: requireNotNull(document).sectionText(node as MappingNode)
    }

    fun sameNode(other: ConfigNodeValue?): Boolean {
        if (other == null || !exists() || !other.exists()) return false
        if (valueDocument == null) return other.valueDocument == null && document === other.document && node === other.node
        if (valueDocument !== other.valueDocument) return false
        if (value is Map<*, *> || value is List<*>) return value === other.value
        return valueParent === other.valueParent && valueKey == other.valueKey && value === other.value
    }
    fun exists(): Boolean = if (valueDocument == null) node != null else valueExists

    fun child(key: String): ConfigNodeValue {
        val childPath = childPath(key)
        if (valueDocument != null) {
            if (!valueExists) return ConfigNodeValue(valueDocument, null, false, childPath, key)
            if (value !is Map<*, *>) throw mappingError("expected a mapping before key '$key'")
            return ConfigNodeValue(valueDocument, value[key], value.containsKey(key), childPath, key, value, key)
        }
        val yaml = requireNotNull(document)
        if (node == null) return ConfigNodeValue(yaml, null, childPath, key)
        if (node !is MappingNode) throw mappingError("expected a mapping before key '$key'")
        return ConfigNodeValue(yaml, YamlDocument.childNode(node, key), childPath, key)
    }

    fun createSection(key: String): ConfigNodeValue {
        if (!isMapping) throw mappingError("expected an existing mapping before key '$key'")
        if (valueDocument != null) {
            ValueDocument.map(value)[key] = linkedMapOf<String, Any?>()
            return child(key)
        }
        val created = MappingNode(Tag.MAP, arrayListOf<NodeTuple>(), DumperOptions.FlowStyle.BLOCK)
        val entries = (node as MappingNode).value
        val index = entries.indexOfFirst { YamlDocument.scalarKey(it.keyNode) == key }
        if (index >= 0) entries[index] = NodeTuple(entries[index].keyNode, created)
        else entries.add(NodeTuple(ScalarNode(Tag.STR, key, null, null, DumperOptions.ScalarStyle.PLAIN), created))
        return child(key, created)
    }

    fun node(relativePath: String): ConfigNodeValue {
        var current = this
        // Java String.split discards trailing empty segments; retain that public path behavior.
        for (segment in java.util.regex.Pattern.compile("\\.").split(relativePath)) {
            require(segment.isNotEmpty()) { "Invalid configuration path: $relativePath" }
            current = current.child(segment)
        }
        return current
    }

    fun keys(): Set<String> {
        if (!exists()) return emptySet()
        if (!isMapping) throw mappingError("expected a mapping")
        val keys = if (valueDocument != null) LinkedHashSet(ValueDocument.map(value).keys)
            else (node as MappingNode).value.mapTo(linkedSetOf()) { YamlDocument.scalarKey(it.keyNode) }
        return Collections.unmodifiableSet(keys)
    }

    fun raw(): Any? {
        if (valueDocument != null) return ValueDocument.snapshot(value)
        return try { raw(node) } catch (failure: ConfigException) { throw failure }
        catch (failure: RuntimeException) { throw mappingError("invalid YAML value", failure) }
    }
    @JvmName("yamlNode") internal fun yamlNode(): Node? = node
    @JvmName("indexed") internal fun indexed(value: Node, index: Int): ConfigNodeValue = ConfigNodeValue(requireNotNull(document), value, "$path[$index]", "[$index]")
    fun index(index: Int): ConfigNodeValue {
        if (!isSequence) throw mappingError("expected a sequence before index '$index'")
        return if (valueDocument == null) indexed((node as SequenceNode).value[index], index)
            else ConfigNodeValue(valueDocument, (value as List<*>)[index], true, "$path[$index]", "[$index]", value, index)
    }
    @get:JvmName("isMapping") internal val isMapping: Boolean get() = if (valueDocument == null) node is MappingNode else valueExists && value is Map<*, *>
    @get:JvmName("isSequence") internal val isSequence: Boolean get() = if (valueDocument == null) node is SequenceNode else valueExists && value is List<*>
    @JvmName("sequenceSize") internal fun sequenceSize(): Int = if (valueDocument == null) (node as SequenceNode).value.size else (value as List<*>).size
    @JvmName("mappingChildren") internal fun mappingChildren(): List<ConfigNodeValue> = if (valueDocument != null) keys().map { child(it) }
        else (node as MappingNode).value.map { tuple ->
            child(YamlDocument.scalarKey(tuple.keyNode), tuple.valueNode).apply { keyMark = ConfigLocations.startMark(tuple.keyNode) }
        }
    @JvmName("keyLocationPrefix") internal fun keyLocationPrefix(): String = ConfigLocations.prefix(sourceName(), path, keyMark)
    @JvmName("child") internal fun child(key: String, value: Node): ConfigNodeValue = ConfigNodeValue(requireNotNull(document), value, childPath(key), key)
    fun mappingViews(): List<ConfigNode> = mappingChildren().map(::ConfigNode)
    private fun childPath(key: String): String = if (path.isEmpty()) key else "$path.$key"

    fun mappingError(detail: String?): ConfigMappingException = failure(detail, ConfigLocations.startMark(node))
    fun mappingError(detail: String?, cause: Throwable?): ConfigMappingException {
        val mark = ConfigLocations.startMark(node)
        return ConfigMappingException(sourceName(), path, ConfigLocations.line(mark), ConfigLocations.column(mark), detail, cause)
    }
    @JvmName("keyMappingError") internal fun keyMappingError(detail: String?): ConfigMappingException = failure(detail, keyMark ?: ConfigLocations.startMark(node))
    private fun failure(detail: String?, mark: Mark?): ConfigMappingException =
        ConfigMappingException(sourceName(), path, ConfigLocations.line(mark), ConfigLocations.column(mark), detail)

    companion object {
        private fun raw(value: Node?): Any? = when {
            value == null || value.tag == Tag.NULL -> null
            value is ScalarNode -> when (value.tag) {
                Tag.BOOL -> value.value.toBoolean()
                Tag.INT -> parseInteger(value.value)
                Tag.FLOAT -> parseFloat(value.value)
                else -> value.value
            }
            value is SequenceNode -> Collections.unmodifiableList(value.value.map { raw(it) })
            value is MappingNode -> Collections.unmodifiableMap(value.value.associateTo(linkedMapOf()) {
                YamlDocument.scalarKey(it.keyNode) to raw(it.valueNode)
            })
            else -> throw ConfigException("Unsupported YAML node: ${value.nodeId}")
        }
        private fun parseInteger(value: String): Number {
            var normalized = value.replace("_", "")
            val negative = normalized.startsWith('-')
            if (normalized.startsWith('-') || normalized.startsWith('+')) normalized = normalized.substring(1)
            if (':' in normalized) throw ConfigException("YAML 1.1 sexagesimal integer '$value' is not supported; quote the value to keep it as a string")
            val radix = when {
                normalized.startsWith("0x") -> 16
                normalized.startsWith("0o") -> 8
                normalized.startsWith("0b") -> 2
                normalized.length > 1 && normalized[0] == '0' -> throw ConfigException(
                    "integer '$value' has a leading zero, which YAML 1.1 treats as octal; write it as 0oNNN for octal or quote the value for a string")
                else -> 10
            }
            if (radix != 10) normalized = normalized.substring(2)
            val parsed = try { ((if (negative) "-" else "") + normalized).toLong(radix) }
                catch (failure: NumberFormatException) { throw ConfigException(
                    "integer '$value' is outside the supported 64-bit range; quote the value to keep it as a string", failure) }
            return if (parsed >= Int.MIN_VALUE && parsed <= Int.MAX_VALUE) parsed.toInt() else parsed
        }
        private fun parseFloat(value: String): Double = when (val normalized = value.replace("_", "").lowercase(Locale.ROOT)) {
            ".inf", "+.inf" -> Double.POSITIVE_INFINITY
            "-.inf" -> Double.NEGATIVE_INFINITY
            ".nan" -> Double.NaN
            else -> normalized.toDouble()
        }
    }
}
