package me.kzheart.klib.command
import me.kzheart.klib.lang.RichText
import org.bukkit.command.CommandSender
class DefaultCommandMessages : CommandMessages {
    private val catalog = mapOf(
        CommandMessageKeys.NO_PERMISSION to "§c你没有权限执行此命令",
        CommandMessageKeys.PLAYER_ONLY to "§c此命令只能由玩家执行",
        CommandMessageKeys.UNKNOWN_ARGUMENT to "§c未知参数 §e{argument}",
        CommandMessageKeys.INCOMPLETE to "§c命令不完整",
        CommandMessageKeys.USAGE to "§e用法: §f{usage}",
        CommandMessageKeys.INTERNAL_ERROR to "§c命令执行出错，请联系管理员（详见控制台）",
        CommandMessageKeys.SUGGESTION to "§7你是不是想输入 §e{suggestion}§7？",
        CommandMessageKeys.HELP_HEADER to "§6§m  §r §e/{command} 帮助 §7({page}/{pages}) §6§m  ",
        CommandMessageKeys.HELP_PREVIOUS to "§8« §e上一页",
        CommandMessageKeys.HELP_NEXT to "§e下一页 §8»",
        CommandMessageKeys.HELP_OPEN_PAGE to "打开第 {page} 页",
        CommandMessageKeys.ARG_INTEGER to "§c需要整数",
        CommandMessageKeys.ARG_INTEGER_RANGE to "§c整数范围: §e{range}",
        CommandMessageKeys.ARG_DECIMAL to "§c需要小数",
        CommandMessageKeys.ARG_DECIMAL_RANGE to "§c小数范围: §e{range}",
        CommandMessageKeys.ARG_BOOLEAN to "§c需要 true/false、yes/no、on/off 或 1/0",
        CommandMessageKeys.ARG_CHOICE to "§c可选值: §e{choices}",
        CommandMessageKeys.ARG_PLAYER_OFFLINE to "§c玩家不在线: §e{player}",
        CommandMessageKeys.ARG_CONTENT_EMPTY to "§c内容不能为空",
        CommandMessageKeys.BUILTIN_HELP_DESCRIPTION to "查看命令帮助",
        CommandMessageKeys.BUILTIN_RELOAD_DESCRIPTION to "重新加载插件配置",
        CommandMessageKeys.BUILTIN_RELOAD_SUCCESS to "§a重新加载完成",
        CommandMessageKeys.BUILTIN_RELOAD_FAILURE to "§c重新加载失败: §f{reason}",
        CommandMessageKeys.BUILTIN_DEBUG_DESCRIPTION to "切换调试模式",
        CommandMessageKeys.BUILTIN_DEBUG_ENABLED to "§a调试模式已开启",
        CommandMessageKeys.BUILTIN_DEBUG_DISABLED to "§7调试模式已关闭"
    )
    override fun resolve(sender: CommandSender, key: String, placeholders: Map<String, *>): RichText {
        val rendered = placeholders.entries.fold(catalog[key] ?: "[missing:$key]") { text, (name, value) ->
            text.replace("{$name}", value.toString())
        }
        return LegacyTextParser.parse(rendered)
    }
    companion object { @JvmField val INSTANCE = DefaultCommandMessages() }
}
