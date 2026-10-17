package me.kzheart.klib.hook.cost

import java.util.Locale

/** 配置条目 type:argument，可用 " | 描述" 覆盖面向玩家的描述。 */
internal class Entry private constructor(val raw: String, val type: String, val argument: String,
                                         val description: String?) {
    companion object {
        private const val DESCRIPTION_SEPARATOR = " | "
        @JvmStatic fun parse(raw: String): Entry {
            var body = raw.trim { it <= ' ' }
            var description: String? = null
            val split = body.lastIndexOf(DESCRIPTION_SEPARATOR)
            if (split >= 0) {
                description = body.substring(split + DESCRIPTION_SEPARATOR.length).trim { it <= ' ' }
                body = body.substring(0, split).trim { it <= ' ' }
                require(description.isNotEmpty()) { "Empty description in '$raw'" }
            }
            val colon = body.indexOf(':')
            require(colon > 0) { "Entry must look like type:argument, got '$raw'" }
            val argument = body.substring(colon + 1).trim { it <= ' ' }
            require(argument.isNotEmpty()) { "Missing argument in '$raw'" }
            return Entry(raw, body.substring(0, colon).trim { it <= ' ' }.lowercase(Locale.ROOT), argument, description)
        }
        /** 解析 ref*3 形式的数量后缀，没有后缀时数量为 1。 */
        @JvmStatic fun amountOf(argument: String): Int {
            val star = argument.lastIndexOf('*')
            if (star < 0) return 1
            val amount = argument.substring(star + 1).trim { it <= ' ' }
            return try {
                val value = amount.toInt()
                require(value >= 1) { "Amount must be positive: $argument" }
                value
            } catch (failure: NumberFormatException) {
                throw IllegalArgumentException("Invalid amount in '$argument'", failure)
            }
        }
        @JvmStatic fun withoutAmount(argument: String): String {
            val star = argument.lastIndexOf('*')
            val reference = (if (star < 0) argument else argument.substring(0, star)).trim { it <= ' ' }
            require(reference.isNotEmpty()) { "Missing item reference in '$argument'" }
            return reference
        }
    }
}
