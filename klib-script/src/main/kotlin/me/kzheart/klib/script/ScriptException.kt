package me.kzheart.klib.script

/** 带源码位置和稳定错误码的本地化失败。 */
class ScriptException internal constructor(private val code: String, message: String, private val line: Int,
    private val column: Int, cause: Throwable?) : RuntimeException(message, cause) {
    fun code(): String = code
    fun line(): Int = line
    fun column(): Int = column
    companion object { private const val serialVersionUID = 1L }
}
