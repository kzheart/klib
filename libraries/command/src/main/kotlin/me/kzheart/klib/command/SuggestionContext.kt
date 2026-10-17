package me.kzheart.klib.command
import java.util.Optional
import me.kzheart.klib.command.api.CommandContext
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
class SuggestionContext(private val context: CommandContext, private val prefix: String) {
    fun sender(): CommandSender = context.sender()
    fun player(): Player = sender() as? Player ?: throw IllegalStateException("Sender is not a player")
    fun prefix(): String = prefix
    fun <T> get(name: String?, type: Class<T>): T = context.get(name, type)
    fun find(name: String?): Optional<Any> = context.find(name)
}
