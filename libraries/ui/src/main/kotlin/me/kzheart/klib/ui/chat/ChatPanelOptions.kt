package me.kzheart.klib.ui.chat

import java.util.EnumMap
import java.util.function.BiConsumer
import me.kzheart.klib.lang.RichText
import org.bukkit.entity.Player

/** 安装期策略集中封装布局预算、有效期、输出与输入适配器。 */
class ChatPanelOptions private constructor(builder: Builder) {
    private val lines = builder.lines
    private val width = builder.width
    private val idleMillis = builder.idleMillis
    private val sender = builder.sender
    private val signInput = builder.signInput
    private val preferSignInput = builder.preferSignInput
    private val signs = builder.signs
    private val texts = EnumMap(builder.texts)
    fun lines(): Int = lines
    fun width(): Int = width
    fun idleMillis(): Long = idleMillis
    @JvmName("sender") internal fun sender(): BiConsumer<Player, RichText>? = sender
    @JvmName("signInput") internal fun signInput(): Boolean = signInput
    @JvmName("preferSignInput") internal fun preferSignInput(): Boolean = preferSignInput
    @JvmName("signs") internal fun signs(): ChatPanelSignInput? = signs
    fun text(key: ChatPanelText): RichText = texts[key] ?: key.defaultValue()
    class Builder internal constructor() {
        internal var lines = 20
        internal var width = 300
        internal var idleMillis = 900000L
        internal var sender: BiConsumer<Player, RichText>? = null
        internal var signInput = true
        internal var preferSignInput = false
        internal var signs: ChatPanelSignInput? = null
        internal val texts = EnumMap<ChatPanelText, RichText>(ChatPanelText::class.java)
        fun lines(value: Int): Builder = apply { require(value in 8..100) { "lines must be 8..100" }; lines = value }
        fun width(value: Int): Builder = apply { require(value in 100..1000) { "width must be 100..1000" }; width = value }
        fun idleMillis(value: Long): Builder = apply { require(value in 10000L..86400000L) { "idle timeout must be 10s..24h" }; idleMillis = value }
        fun sender(value: BiConsumer<Player, RichText>): Builder = apply { sender = value }
        fun signInput(enabled: Boolean): Builder = apply { signInput = enabled }
        fun preferSignInput(enabled: Boolean): Builder = apply { preferSignInput = enabled }
        fun signInput(value: ChatPanelSignInput): Builder = apply { signs = value; signInput = true }
        fun text(key: ChatPanelText, value: RichText): Builder = apply { texts[key] = value }
        fun build(): ChatPanelOptions = ChatPanelOptions(this)
    }
    companion object {
        @JvmStatic fun defaults(): ChatPanelOptions = builder().build()
        @JvmStatic fun builder(): Builder = Builder()
    }
}
