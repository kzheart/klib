package me.kzheart.klib.script

import java.math.BigDecimal
import java.util.regex.Matcher
import java.util.regex.Pattern

class InlineValues private constructor() {
    companion object {
        private val VARIABLE = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}|\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}")
        @JvmStatic fun value(source: String, context: ScriptContext): Any? {
            if (source.length > 1 && source[0] == '&') return context.variableOrNull(source.substring(1))
            if (source.length > 1 && source[0] == '*') return source.substring(1)
            val matcher = VARIABLE.matcher(source)
            if (matcher.matches()) return context.variableOrNull(variableName(matcher))
            val expanded = text(source, context)
            return when {
                expanded.equals("true", true) -> true
                expanded.equals("false", true) -> false
                expanded.equals("null", true) -> null
                else -> try { BigDecimal(expanded) } catch (_: NumberFormatException) { expanded }
            }
        }
        @JvmStatic fun text(source: String, context: ScriptContext): String = when {
            source.length > 1 && source[0] == '&' -> context.variableOrNull(source.substring(1))?.toString() ?: ""
            source.length > 1 && source[0] == '*' -> source.substring(1)
            else -> interpolate(source, context)
        }
        @JvmStatic fun interpolate(source: String, context: ScriptContext): String {
            val matcher = VARIABLE.matcher(source)
            val result = StringBuffer()
            while (matcher.find()) matcher.appendReplacement(result,
                Matcher.quoteReplacement(context.variableOrNull(variableName(matcher))?.toString() ?: ""))
            matcher.appendTail(result)
            if (result.length > 1 && result[0] == '$' && result.substring(1).matches(Regex("[A-Za-z0-9_.-]+"))) {
                return context.variableOrNull(result.substring(1))?.toString() ?: ""
            }
            return result.toString()
        }
        @JvmStatic fun truthy(value: Any?): Boolean = when (value) {
            is Boolean -> value
            is Number -> BigDecimal(value.toString()).compareTo(BigDecimal.ZERO) != 0
            is CharSequence -> value.toString().let { it.isNotEmpty() && !it.equals("false", true) && it != "0" }
            else -> value != null
        }
        private fun variableName(matcher: Matcher): String = matcher.group(1) ?: matcher.group(2)
    }
}
