package me.kzheart.klib.command
import java.util.concurrent.CompletionStage
import java.util.function.Consumer
import me.kzheart.klib.command.api.CommandContext
import me.kzheart.klib.scheduler.AsyncTasks
import me.kzheart.klib.scope.Scope
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
class CommandCall(private val context: CommandContext, private val owner: Scope) {
    fun sender(): CommandSender = context.sender()
    fun <T> get(name: String?, type: Class<T>): T = context.get(name, type)
    fun reply(message: String) {
        val sender = sender()
        if (!owner.isClosed && (sender !is Player || sender.isOnline)) sender.sendMessage(message)
    }
    fun <T> await(stage: CompletionStage<T>, success: Consumer<in T>, failure: Consumer<in Throwable>): CompletionStage<T> =
        AsyncTasks.thenSync(stage, owner, success, failure)
}
