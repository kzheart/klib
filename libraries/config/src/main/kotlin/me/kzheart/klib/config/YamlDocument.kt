package me.kzheart.klib.config

import java.io.StringReader
import java.io.StringWriter
import java.util.IdentityHashMap
import java.util.logging.Logger
import java.util.regex.Pattern
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.MarkedYAMLException
import org.yaml.snakeyaml.nodes.AnchorNode
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.NodeTuple
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer

/** Mutable comment-preserving YAML syntax tree. */
class YamlDocument private constructor(private val sourceName: String, private val yaml: Yaml, private val root: MappingNode) {
    fun sourceName(): String = sourceName
    fun root(): ConfigNode = ConfigNode(this, root, "")
    fun node(path: String): ConfigNode = if (path.isEmpty()) root() else splitPath(path).fold(root()) { node, key -> node.child(key) }
    fun mergeDefaults(defaults: YamlDocument): YamlDocument = apply { mergeMappings(root, defaults.root) }
    @JvmName("mergeDefaultsChanged") internal fun mergeDefaultsChanged(defaults: YamlDocument): Boolean = mergeMappings(root, defaults.root)
    fun schemaVersion(): Int {
        val version = node("_schema-version")
        if (!version.exists()) return 0
        val value = version.raw() as? Number ?: throw version.mappingError("schema version must be an integer")
        val parsed = value.toLong()
        if (parsed < 0 || parsed > Int.MAX_VALUE) throw version.mappingError("schema version is outside integer range")
        return parsed.toInt()
    }
    fun toYaml(): String = sectionText(root)
    @JvmName("sectionText") internal fun sectionText(section: MappingNode): String = StringWriter().also { yaml.serialize(section, it) }.toString()
    @JvmName("rootNode") internal fun rootNode(): MappingNode = root
    @JvmName("copy") internal fun copy(copySourceName: String): YamlDocument = YamlDocument(copySourceName, yaml, copyNode(root) as MappingNode)
    @JvmName("findNode") internal fun findNode(path: String): Node? = node(path).yamlNode()

    @JvmName("rename") internal fun rename(sourcePath: String, targetPath: String) {
        val source = splitPath(sourcePath)
        val target = splitPath(targetPath)
        if (sourcePath == targetPath) return
        val sourceParent = findMappingParent(source, false) ?: return
        val sourceIndex = tupleIndex(sourceParent, source.last())
        if (sourceIndex < 0) return
        val sourceTuple = sourceParent.value[sourceIndex]
        val targetParent = requireNotNull(findMappingParent(target, true))
        val targetKey = target.last()
        val existingIndex = tupleIndex(targetParent, targetKey)
        if (existingIndex >= 0) {
            if (!nodeEquals(sourceTuple.valueNode, targetParent.value[existingIndex].valueNode)) {
                logger.warning("$sourceName: rename '$sourcePath' -> '$targetPath' dropped the source value because the target key already exists with a different value")
            }
            sourceParent.value.removeAt(sourceIndex)
            return
        }
        val renamedKey = scalarKey(targetKey).also { copyComments(sourceTuple.keyNode, it) }
        sourceParent.value.removeAt(sourceIndex)
        targetParent.value.add(NodeTuple(renamedKey, sourceTuple.valueNode))
    }

    @JvmName("setInteger") internal fun setInteger(path: String, value: Int) {
        val segments = splitPath(path)
        val parent = requireNotNull(findMappingParent(segments, true))
        val replacement = NodeTuple(scalarKey(segments.last()), ScalarNode(Tag.INT, value.toString(), null, null, DumperOptions.ScalarStyle.PLAIN))
        val index = tupleIndex(parent, segments.last())
        if (index < 0) parent.value.add(replacement)
        else {
            copyComments(parent.value[index].keyNode, replacement.keyNode)
            parent.value[index] = replacement
        }
    }

    private fun findMappingParent(path: Array<String>, create: Boolean): MappingNode? {
        var current = root
        for (index in 0 until path.lastIndex) {
            val segment = path[index]
            current = when (val child = childNode(current, segment)) {
                null -> {
                    if (!create) return null
                    emptyMapping().also { current.value.add(NodeTuple(scalarKey(segment), it)) }
                }
                is MappingNode -> child
                else -> throw ConfigException("$sourceName:${path.take(index + 1).joinToString(".")}: expected a mapping while applying migration")
            }
        }
        return current
    }

    companion object {
        private val logger = Logger.getLogger(YamlDocument::class.java.name)
        @JvmStatic fun parse(sourceName: String, content: String): YamlDocument {
            val loader = LoaderOptions().apply { isAllowDuplicateKeys = false; isProcessComments = true }
            val dumper = DumperOptions().apply {
                isProcessComments = true
                defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
                isPrettyFlow = true
                indent = 2
                indicatorIndent = 2
                setIndentWithIndicator(true)
                setSplitLines(false)
            }
            val yaml = Yaml(SafeConstructor(loader), Representer(dumper), dumper, loader)
            val composed = try { yaml.compose(StringReader(content)) }
            catch (failure: MarkedYAMLException) {
                throw ConfigException("${ConfigLocations.prefix(sourceName, "", failure.problemMark ?: failure.contextMark)}: invalid YAML", failure)
            } catch (failure: RuntimeException) { throw ConfigException("$sourceName:<root>: invalid YAML", failure) }
            if (composed == null) return YamlDocument(sourceName, yaml, emptyMapping())
            if (composed !is MappingNode) throw ConfigException("$sourceName:<root>: the YAML root must be a mapping")
            visitNode(sourceName, composed, IdentityHashMap(), IdentityHashMap())
            return YamlDocument(sourceName, yaml, composed)
        }

        private fun visitNode(source: String, candidate: Node, active: MutableMap<Node, Boolean>, complete: MutableMap<Node, Boolean>) {
            val node = if (candidate is AnchorNode) candidate.realNode else candidate
            if (complete.containsKey(node)) return
            if (active.put(node, true) != null) throw ConfigException("$source:<root>: recursive YAML aliases are not supported")
            when (node) {
                is SequenceNode -> node.value.forEach { visitNode(source, it, active, complete) }
                is MappingNode -> node.value.forEach { visitNode(source, it.keyNode, active, complete); visitNode(source, it.valueNode, active, complete) }
            }
            active.remove(node)
            complete[node] = true
        }

        @JvmStatic @JvmName("childNode") internal fun childNode(mapping: MappingNode, key: String): Node? = mapping.value.getOrNull(tupleIndex(mapping, key))?.valueNode
        @JvmStatic @JvmName("scalarKey") internal fun scalarKey(keyNode: Node): String = (keyNode as? ScalarNode)?.value
            ?: throw ConfigException("Only scalar YAML keys are supported")

        private fun mergeMappings(target: MappingNode, defaults: MappingNode): Boolean {
            var changed = false
            for (defaultTuple in defaults.value) {
                val index = tupleIndex(target, scalarKey(defaultTuple.keyNode))
                if (index < 0) { target.value.add(copyTuple(defaultTuple)); changed = true; continue }
                val targetValue = target.value[index].valueNode
                val defaultValue = defaultTuple.valueNode
                if (targetValue is MappingNode && defaultValue is MappingNode) {
                    val nestedChanged = mergeMappings(targetValue, defaultValue)
                    changed = changed || nestedChanged
                }
            }
            return changed
        }
        private fun copyTuple(tuple: NodeTuple): NodeTuple = NodeTuple(copyNode(tuple.keyNode), copyNode(tuple.valueNode))
        private fun copyNode(node: Node): Node {
            val copy = when (node) {
                is ScalarNode -> ScalarNode(node.tag, node.value, null, null, node.scalarStyle)
                is SequenceNode -> SequenceNode(node.tag, node.value.mapTo(arrayListOf()) { copyNode(it) }, node.flowStyle)
                is MappingNode -> MappingNode(node.tag, node.value.mapTo(arrayListOf()) { copyTuple(it) }, node.flowStyle)
                is AnchorNode -> AnchorNode(copyNode(node.realNode))
                else -> throw ConfigException("Unsupported YAML node: ${node.nodeId}")
            }
            copyComments(node, copy)
            return copy
        }
        private fun copyComments(source: Node, target: Node) {
            target.blockComments = source.blockComments?.let { ArrayList(it) }
            target.inLineComments = source.inLineComments?.let { ArrayList(it) }
            target.endComments = source.endComments?.let { ArrayList(it) }
        }
        private fun nodeEquals(left: Node, right: Node): Boolean {
            val first = if (left is AnchorNode) left.realNode else left
            val second = if (right is AnchorNode) right.realNode else right
            return when {
                first is ScalarNode && second is ScalarNode -> first.tag == second.tag && first.value == second.value
                first is SequenceNode && second is SequenceNode -> first.value.size == second.value.size &&
                    first.value.indices.all { nodeEquals(first.value[it], second.value[it]) }
                first is MappingNode && second is MappingNode -> first.value.size == second.value.size && first.value.all {
                    val match = childNode(second, scalarKey(it.keyNode))
                    match != null && nodeEquals(it.valueNode, match)
                }
                else -> false
            }
        }
        private fun tupleIndex(mapping: MappingNode, key: String): Int = mapping.value.indexOfFirst { key == scalarKey(it.keyNode) }
        private fun emptyMapping(): MappingNode = MappingNode(Tag.MAP, arrayListOf<NodeTuple>(), DumperOptions.FlowStyle.BLOCK)
        private fun scalarKey(key: String): ScalarNode = ScalarNode(Tag.STR, key, null, null, DumperOptions.ScalarStyle.PLAIN)
        private fun splitPath(path: String): Array<String> {
            require(path.trim().isNotEmpty()) { "Configuration path must not be blank" }
            return Pattern.compile("\\.").split(path).also { segments ->
                require(segments.none { it.isEmpty() }) { "Invalid configuration path: $path" }
            }
        }
    }
}
