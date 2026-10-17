package me.kzheart.klib.command
import me.kzheart.klib.lang.RichText
import org.bukkit.command.CommandSender
class PlainRichTextSink private constructor() : RichTextSink {
    override fun send(sender: CommandSender, text: RichText) { sender.sendMessage(text.plainText()) }
    companion object { @JvmField val INSTANCE = PlainRichTextSink() }
}
