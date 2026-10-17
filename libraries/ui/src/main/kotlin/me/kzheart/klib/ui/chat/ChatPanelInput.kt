package me.kzheart.klib.ui.chat

import java.util.Optional
import me.kzheart.klib.lang.RichText
import me.kzheart.klib.scheduler.Ticks
import me.kzheart.klib.ui.prompt.PromptParser

/** 输入策略不可变；解析器在异步聊天线程调用，仅做纯解析。 */
class ChatPanelInput<T> private constructor(private val parser: PromptParser<T>, private val hint: RichText?,
    private val current: String, private val timeout: Ticks, private val cancelKeyword: String, private val sign: Boolean) {
    fun hint(value: RichText): ChatPanelInput<T> = ChatPanelInput(parser, value, current, timeout, cancelKeyword, sign)
    fun current(value: String?): ChatPanelInput<T> = ChatPanelInput(parser, hint, value ?: "", timeout, cancelKeyword, sign)
    fun timeout(value: Ticks): ChatPanelInput<T> = ChatPanelInput(parser, hint, current, value, cancelKeyword, sign)
    fun cancelKeyword(value: String): ChatPanelInput<T> = ChatPanelInput(parser, hint, current, timeout, value, sign)
    fun sign(allowed: Boolean): ChatPanelInput<T> = ChatPanelInput(parser, hint, current, timeout, cancelKeyword, allowed)
    @JvmName("parser") internal fun parser(): PromptParser<T> = parser
    @JvmName("hint") internal fun hint(): RichText? = hint
    @JvmName("current") internal fun current(): String = current
    @JvmName("timeout") internal fun timeout(): Ticks = timeout
    @JvmName("cancelKeyword") internal fun cancelKeyword(): String = cancelKeyword
    @JvmName("signAllowed") internal fun signAllowed(): Boolean = sign
    companion object {
        @JvmStatic fun <T> of(parser: PromptParser<T>): ChatPanelInput<T> = ChatPanelInput(parser, null, "", Ticks.seconds(60), "cancel", true)
        @JvmStatic fun text(maxLength: Int): ChatPanelInput<String> {
            require(maxLength > 0) { "maxLength must be positive" }
            return of { value -> if (value.trim().isEmpty() || value.length > maxLength) Optional.empty() else Optional.of(value) }
        }
    }
}
