package me.kzheart.klib.command;

import me.kzheart.klib.lang.RichText;
import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DefaultCommandMessages implements CommandMessages {
    public static final DefaultCommandMessages INSTANCE = new DefaultCommandMessages();

    private final Map<String, String> catalog;

    public DefaultCommandMessages() {
        Map<String, String> messages = new LinkedHashMap<String, String>();
        messages.put(CommandMessageKeys.NO_PERMISSION, "§c你没有权限执行此命令");
        messages.put(CommandMessageKeys.PLAYER_ONLY, "§c此命令只能由玩家执行");
        messages.put(CommandMessageKeys.UNKNOWN_ARGUMENT, "§c未知参数 §e{argument}");
        messages.put(CommandMessageKeys.INCOMPLETE, "§c命令不完整");
        messages.put(CommandMessageKeys.USAGE, "§e用法: §f{usage}");
        messages.put(CommandMessageKeys.INTERNAL_ERROR, "§c命令执行出错，请联系管理员（详见控制台）");
        messages.put(CommandMessageKeys.SUGGESTION, "§7你是不是想输入 §e{suggestion}§7？");
        messages.put(
                CommandMessageKeys.HELP_HEADER,
                "§6§m  §r §e/{command} 帮助 §7({page}/{pages}) §6§m  ");
        messages.put(CommandMessageKeys.HELP_PREVIOUS, "§8« §e上一页");
        messages.put(CommandMessageKeys.HELP_NEXT, "§e下一页 §8»");
        messages.put(CommandMessageKeys.HELP_OPEN_PAGE, "打开第 {page} 页");
        messages.put(CommandMessageKeys.ARG_INTEGER, "§c需要整数");
        messages.put(CommandMessageKeys.ARG_INTEGER_RANGE, "§c整数范围: §e{range}");
        messages.put(CommandMessageKeys.ARG_DECIMAL, "§c需要小数");
        messages.put(CommandMessageKeys.ARG_DECIMAL_RANGE, "§c小数范围: §e{range}");
        messages.put(CommandMessageKeys.ARG_BOOLEAN, "§c需要 true/false、yes/no、on/off 或 1/0");
        messages.put(CommandMessageKeys.ARG_CHOICE, "§c可选值: §e{choices}");
        messages.put(CommandMessageKeys.ARG_PLAYER_OFFLINE, "§c玩家不在线: §e{player}");
        messages.put(CommandMessageKeys.ARG_CONTENT_EMPTY, "§c内容不能为空");
        messages.put(CommandMessageKeys.BUILTIN_HELP_DESCRIPTION, "查看命令帮助");
        messages.put(CommandMessageKeys.BUILTIN_RELOAD_DESCRIPTION, "重新加载插件配置");
        messages.put(CommandMessageKeys.BUILTIN_RELOAD_SUCCESS, "§a重新加载完成");
        messages.put(CommandMessageKeys.BUILTIN_RELOAD_FAILURE, "§c重新加载失败: §f{reason}");
        messages.put(CommandMessageKeys.BUILTIN_DEBUG_DESCRIPTION, "切换调试模式");
        messages.put(CommandMessageKeys.BUILTIN_DEBUG_ENABLED, "§a调试模式已开启");
        messages.put(CommandMessageKeys.BUILTIN_DEBUG_DISABLED, "§7调试模式已关闭");
        catalog = Collections.unmodifiableMap(messages);
    }

    @Override
    public RichText resolve(CommandSender sender, String key, Map<String, ?> placeholders) {
        String template = catalog.containsKey(key) ? catalog.get(key) : "[missing:" + key + "]";
        String rendered = template;
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            rendered = rendered.replace(
                    "{" + entry.getKey() + "}",
                    String.valueOf(entry.getValue()));
        }
        return LegacyTextParser.parse(rendered);
    }
}
