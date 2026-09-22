package me.kzheart.klib.command;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import me.kzheart.klib.command.api.CommandContext;
import me.kzheart.klib.scheduler.AsyncTasks;
import me.kzheart.klib.scope.Scope;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
/** Main-thread command context bound to the command registration lifetime. */
public final class CommandCall {
    private final CommandContext context;
    private final Scope owner;
    public CommandCall(CommandContext context, Scope owner) { this.context = context; this.owner = owner; }
    public CommandSender sender() { return context.sender(); }
    public <T> T get(String name, Class<T> type) { return context.get(name, type); }
    public void reply(String message) {
        if (!owner.isClosed() && (!(sender() instanceof Player) || ((Player) sender()).isOnline())) {
            sender().sendMessage(message);
        }
    }
    /** Registers both outcomes atomically, even when the stage has already completed. */
    public <T> CompletionStage<T> await(CompletionStage<T> stage, Consumer<? super T> success,
            Consumer<? super Throwable> failure) {
        return AsyncTasks.thenSync(stage, owner, success, failure);
    }
}
