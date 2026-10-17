package me.kzheart.klib.command
import me.kzheart.klib.lang.RichText
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
class ConsoleAwareRichTextSink(private val playerSink: RichTextSink) : RichTextSink {
    override fun send(sender: CommandSender, text: RichText) =
        (if (sender is Player) playerSink else PlainRichTextSink.INSTANCE).send(sender, text)
}
