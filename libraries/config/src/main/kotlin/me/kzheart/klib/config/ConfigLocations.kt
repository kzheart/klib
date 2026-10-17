package me.kzheart.klib.config
import org.yaml.snakeyaml.error.Mark
import org.yaml.snakeyaml.nodes.Node
internal class ConfigLocations private constructor() {
    companion object {
        @JvmStatic fun line(mark: Mark?): Int = mark?.line?.plus(1) ?: ConfigMappingException.UNKNOWN_POSITION
        @JvmStatic fun column(mark: Mark?): Int = mark?.column?.plus(1) ?: ConfigMappingException.UNKNOWN_POSITION
        @JvmStatic fun startMark(node: Node?): Mark? = node?.startMark
        @JvmStatic fun prefix(sourceName: String, path: String?, line: Int, column: Int): String {
            val displayPath = path?.takeUnless { it.isEmpty() } ?: "<root>"
            return if (line <= 0) "$sourceName:$displayPath"
                else "$sourceName:$line" + (if (column > 0) ":$column" else "") + " ($displayPath)"
        }
        @JvmStatic fun prefix(sourceName: String, path: String?, mark: Mark?): String = prefix(sourceName, path, line(mark), column(mark))
    }
}
