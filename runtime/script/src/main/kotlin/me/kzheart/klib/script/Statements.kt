package me.kzheart.klib.script

/** 位置参数组合器，声明冻结后解析器独立于后续 builder 修改。 */
class Statements private constructor() {
    class Combination internal constructor() {
        private val parts = ArrayList<Part>()
        private var remainingDeclared = false
        fun required(name: String): Combination = append(Part(requireName(name), null, false))
        fun optional(name: String, defaultValue: String): Combination = append(Part(requireName(name), defaultValue, false))
        fun remaining(name: String): Combination = append(Part(requireName(name), "", true)).also { remainingDeclared = true }
        private fun append(part: Part): Combination {
            check(!remainingDeclared) { "No argument may follow remaining()" }
            parts.add(part)
            return this
        }
        fun execute(action: CombinedAction): QuestActionParser {
            val definition = parts.toList()
            return QuestActionParser { call, context -> action.execute(parse(definition, call, context), context) }
        }
        private fun parse(definition: List<Part>, call: StatementCall, context: ScriptContext): StatementArguments {
            val values = LinkedHashMap<String, String>()
            var index = 0
            for (part in definition) values[part.name] = when {
                part.remaining -> call.text(index, context).also { index = call.arguments().size }
                index < call.arguments().size -> call.argument(index++)
                part.defaultValue != null -> part.defaultValue
                else -> throw IllegalArgumentException(ScriptMessages.text(context.locale(), "missing-argument", part.name))
            }
            require(index >= call.arguments().size) { "Unexpected argument: ${call.argument(index)}" }
            return StatementArguments(values)
        }
        private fun requireName(name: String): String = name.trim { it <= ' ' }.also { require(it.isNotEmpty()) { "Argument name must not be blank" } }
    }
    private class Part(val name: String, val defaultValue: String?, val remaining: Boolean)
    companion object { @JvmStatic fun combine(): Combination = Combination() }
}
