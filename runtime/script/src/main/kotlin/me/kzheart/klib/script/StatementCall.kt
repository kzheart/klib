package me.kzheart.klib.script

import java.util.Collections
import java.util.concurrent.CompletionStage

/** 解析后的语句，包含位置和共享嵌套求值入口。 */
internal class StatementCallValues(private val name: String, arguments: List<String>, private val line: Int,
    private val column: Int, private val nestedEvaluator: StatementCall.NestedEvaluator) {
    private val arguments = Collections.unmodifiableList(arguments)
    fun name(): String = name
    fun arguments(): List<String> = arguments
    fun argument(index: Int): String {
        require(index in arguments.indices) { "Missing statement argument at index $index" }
        return arguments[index]
    }
    fun value(index: Int, context: ScriptContext): Any? = InlineValues.value(argument(index), context)
    fun text(startIndex: Int, context: ScriptContext): String {
        require(startIndex in 0..arguments.size) { "Invalid statement argument index $startIndex" }
        // 旧行为只在已有非空正文后插入空格，空参数不引入前导空格。
        return buildString {
            for (index in startIndex until arguments.size) {
                if (isNotEmpty()) append(' ')
                append(InlineValues.text(arguments[index], context))
            }
        }
    }
    fun line(): Int = line
    fun column(): Int = column
    fun eval(source: String, context: ScriptContext): CompletionStage<Any?> = nestedEvaluator.eval(source, context)
}
