package me.kzheart.klib.script.taboolib.common

/** Bean-shaped methods are consumed reflectively across class loaders. */
class OpenResult private constructor(val isSuccessful: Boolean, val value: Any?) {
    @JvmSynthetic @JvmName("successfulValue") fun isSuccessful(): Boolean = isSuccessful
    @JvmSynthetic @JvmName("valueValue") fun getValue(): Any? = value
    companion object {
        private val FAILED = OpenResult(false, null)
        @JvmStatic fun successful(value: Any?): OpenResult = OpenResult(true, value)
        @JvmStatic fun failed(): OpenResult = FAILED
    }
}
