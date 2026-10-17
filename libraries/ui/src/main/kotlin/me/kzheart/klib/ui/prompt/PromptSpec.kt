package me.kzheart.klib.ui.prompt

import me.kzheart.klib.scheduler.Ticks

/** 提示配置与 Bukkit 监听器相互独立。 */
class PromptSpec<T> private constructor(builder: Builder<T>) {
    private val parser = builder.parser
    private val timeout = builder.timeout
    private val cancelKeyword = builder.cancelKeyword
    private val invalidMessage = builder.invalidMessage
    private val cancelledMessage = builder.cancelledMessage
    private val timeoutMessage = builder.timeoutMessage
    fun parser(): PromptParser<T> = parser
    fun timeout(): Ticks = timeout
    fun cancelKeyword(): String = cancelKeyword
    fun invalidMessage(): String = invalidMessage
    fun cancelledMessage(): String = cancelledMessage
    fun timeoutMessage(): String = timeoutMessage
    class Builder<T> internal constructor(internal val parser: PromptParser<T>) {
        internal var timeout = Ticks.seconds(30)
        internal var cancelKeyword = "cancel"
        internal var invalidMessage = "格式不正确，请重新输入（输入 cancel 取消）"
        internal var cancelledMessage = "已取消输入"
        internal var timeoutMessage = "输入超时，已取消"
        fun timeout(value: Ticks): Builder<T> = apply { timeout = value }
        fun cancelKeyword(value: String): Builder<T> = apply { cancelKeyword = value }
        fun invalidMessage(value: String): Builder<T> = apply { invalidMessage = value }
        fun cancelledMessage(value: String): Builder<T> = apply { cancelledMessage = value }
        fun timeoutMessage(value: String): Builder<T> = apply { timeoutMessage = value }
        fun build(): PromptSpec<T> = PromptSpec(this)
    }
    companion object { @JvmStatic fun <T> builder(parser: PromptParser<T>): Builder<T> = Builder(parser) }
}
