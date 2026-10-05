package io.papermc.paper.command.brigadier;

import java.util.Collection;
import org.bukkit.command.CommandSender;

/** 测试中的公开 Paper API 签名；不会进入发布产物。 */
public interface BasicCommand {
    void execute(CommandSourceStack source, String[] args);
    Collection<String> suggest(CommandSourceStack source, String[] args);
    boolean canUse(CommandSender sender);
    String permission();
}
