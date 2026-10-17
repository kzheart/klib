package me.kzheart.klib.script

import java.text.MessageFormat
import java.util.Locale

class ScriptMessages private constructor() {
    companion object {
        private val ZH = mapOf(
            "unknown-statement" to "第 {0} 行第 {1} 列：未知语句“{2}”",
            "invalid-syntax" to "第 {0} 行第 {1} 列：语法错误：{2}",
            "action-failed" to "第 {0} 行第 {1} 列：语句“{2}”执行失败：{3}",
            "invalid-condition" to "条件结果不是布尔值：{0}",
            "missing-argument" to "缺少参数“{0}”",
            "invalid-number" to "“{0}”不是有效数字",
            "continuation-executor-required" to "异步脚本动作需要显式提供续接 Executor"
        )
        private val EN = mapOf(
            "unknown-statement" to "Line {0}, column {1}: unknown statement ''{2}''",
            "invalid-syntax" to "Line {0}, column {1}: syntax error: {2}",
            "action-failed" to "Line {0}, column {1}: statement ''{2}'' failed: {3}",
            "invalid-condition" to "Condition result is not boolean: {0}",
            "missing-argument" to "Missing argument ''{0}''",
            "invalid-number" to "''{0}'' is not a valid number",
            "continuation-executor-required" to "Asynchronous script actions require an explicit continuation Executor"
        )
        @JvmStatic fun text(locale: Locale, key: String, vararg arguments: Any?): String =
            MessageFormat.format((if (locale.language == "zh") ZH else EN)[key] ?: key, *arguments)
    }
}
