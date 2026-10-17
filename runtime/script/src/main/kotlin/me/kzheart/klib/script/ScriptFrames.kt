package me.kzheart.klib.script

import me.kzheart.klib.script.kether.core.QuestContext
import java.util.Collections
import java.util.concurrent.CompletionStage

/** 原生动作访问宿主的入口；保留帧父链、内部变量屏蔽和嵌套求值预算。 */
class ScriptFrames private constructor() {
    companion object {
        @JvmStatic fun context(frame: QuestContext.Frame): ScriptContext = CoreScriptRuntime.context(frame)
        @JvmStatic fun variables(frame: QuestContext.Frame): Map<String, Any?> {
            val tables = ArrayList<QuestContext.VarTable>()
            var table: QuestContext.VarTable? = frame.variables()
            while (table != null) { tables.add(table); table = table.parent() }
            val result = LinkedHashMap<String, Any?>()
            for (current in tables.asReversed()) {
                for ((key, value) in ForeignVarTableEntries.snapshot(current)) {
                    if (!isInternal(key)) result[key] = value
                }
            }
            return Collections.unmodifiableMap(result)
        }
        @JvmStatic fun eval(frame: QuestContext.Frame, source: String): CompletionStage<Any?> = CoreScriptRuntime.evalNested(frame, source)
        @JvmStatic fun isInternal(name: String): Boolean = name.startsWith("~klib:")
        @JvmStatic fun checkWritable(name: String) { require(!isInternal(name)) { "Reserved script variable: $name" } }
    }
}
