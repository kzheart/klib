/* VariableReader/KetherFunction semantics: Copyright (c) 2018 Bkm016, MIT.
 * See THIRD_PARTY_NOTICES.md. */
package me.kzheart.klib.script

import java.util.concurrent.CompletionStage
import java.util.function.Function

/** 显式即时求值的嵌套模板；未完成阶段保持原 "null" 文本语义，不等待或取消。 */
class ScriptTemplates private constructor() {
    companion object {
        private const val START = "{{"
        private const val END = "}}"
        @JvmStatic fun renderImmediate(source: String, evaluator: Function<String, out CompletionStage<*>>): String {
            var result = source
            while (true) {
                val end = indexOf(result, END)
                if (end < 0) break
                val start = lastIndexOf(result.substring(0, end), START)
                if (start < 0) break
                val expression = format(result.substring(start + START.length, end))
                val value = evaluator.apply(expression).toCompletableFuture().getNow(null)
                result = result.substring(0, start) + value.toString() + result.substring(end + END.length)
            }
            return format(result)
        }
        private fun indexOf(source: String, marker: String): Int {
            var position = 0
            while (true) {
                val found = source.indexOf(marker, position)
                if (found <= 0 || source[found - 1] != '\\') return found
                position = found + marker.length
            }
        }
        private fun lastIndexOf(source: String, marker: String): Int {
            var position = source.length
            while (true) {
                val found = source.lastIndexOf(marker, position)
                if (found <= 0 || source[found - 1] != '\\') return found
                position = found - marker.length
            }
        }
        private fun format(source: String): String = buildString {
            var index = 0
            while (index < source.length) {
                if (source[index] == '\\' && (source.startsWith(START, index + 1) || source.startsWith(END, index + 1))) {
                    append(source, index + 1, index + 3); index += 3
                } else append(source[index++])
            }
        }
    }
}
