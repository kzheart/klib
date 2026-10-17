package me.kzheart.klib.hook

/** 钩子实现共用的文本校验工具。 */
class Texts private constructor() {
    companion object {
        /** 返回去除首尾空白后的值，并拒绝 null 或空白输入。 */
        @JvmStatic
        fun requireText(value: String, name: String): String {
            val normalized = value.trim { it <= ' ' }
            require(normalized.isNotEmpty()) { "$name must not be blank" }
            return normalized
        }
    }
}
