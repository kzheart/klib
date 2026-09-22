package me.kzheart.klib.command;
import me.kzheart.klib.command.api.CommandContext;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
public final class SuggestionContext {
    private final CommandContext context;
    private final String prefix;
    public SuggestionContext(CommandContext context, String prefix) { this.context = context; this.prefix = prefix; }
    public CommandSender sender() { return context.sender(); }
    public Player player() {
        if (!(sender() instanceof Player)) throw new IllegalStateException("Sender is not a player");
        return (Player) sender();
    }
    public String prefix() { return prefix; }
    public <T> T get(String name, Class<T> type) { return context.get(name, type); }
    public java.util.Optional<Object> find(String name) { return context.find(name); }
}
