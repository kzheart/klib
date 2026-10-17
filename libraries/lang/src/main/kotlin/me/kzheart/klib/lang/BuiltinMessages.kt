package me.kzheart.klib.lang

import java.util.Locale

/** 插件提供覆盖项之前，各模块可用的通用消息键。 */
class BuiltinMessages private constructor() {
    companion object {
        const val NO_PERMISSION = "common.no-permission"
        const val PLAYER_ONLY = "common.player-only"
        const val COMMAND_NO_PERMISSION = "command.no-permission"
        const val COMMAND_PLAYER_ONLY = "command.player-only"
        const val COMMAND_INCOMPLETE = "command.incomplete"
        const val COMMAND_USAGE = "command.usage"
        const val COMMAND_INTERNAL_ERROR = "command.internal-error"
        const val UNKNOWN_ARGUMENT = "command.unknown-argument"
        const val SUGGESTION = "command.suggestion"
        const val HELP_HEADER = "command.help.header"
        const val HELP_PREVIOUS = "command.help.previous"
        const val HELP_NEXT = "command.help.next"
        const val HELP_OPEN_PAGE = "command.help.open-page"
        const val ARG_INTEGER = "command.argument.integer"
        const val ARG_INTEGER_RANGE = "command.argument.integer-range"
        const val ARG_DECIMAL = "command.argument.decimal"
        const val ARG_DECIMAL_RANGE = "command.argument.decimal-range"
        const val ARG_BOOLEAN = "command.argument.boolean"
        const val ARG_CHOICE = "command.argument.choice"
        const val ARG_PLAYER_OFFLINE = "command.argument.player-offline"
        const val ARG_CONTENT_EMPTY = "command.argument.content-empty"
        const val BUILTIN_HELP_DESCRIPTION = "command.builtin.help.description"
        const val BUILTIN_RELOAD_DESCRIPTION = "command.builtin.reload.description"
        const val BUILTIN_RELOAD_SUCCESS = "command.builtin.reload.success"
        const val BUILTIN_RELOAD_FAILURE = "command.builtin.reload.failure"
        const val BUILTIN_DEBUG_DESCRIPTION = "command.builtin.debug.description"
        const val BUILTIN_DEBUG_ENABLED = "command.builtin.debug.enabled"
        const val BUILTIN_DEBUG_DISABLED = "command.builtin.debug.disabled"

        @JvmStatic fun catalog(): MessageCatalog = catalog(null)
        /** en* 地区使用英文，其余地区使用中文。 */
        @JvmStatic fun catalog(locale: String?): MessageCatalog = MapMessageCatalog(
            if (locale?.trim()?.lowercase(Locale.ROOT)?.startsWith("en") == true) englishValues() else chineseValues())
        private fun chineseValues(): Map<String, Any> = linkedMapOf(
            NO_PERMISSION to "<red>你没有权限执行此操作</red>",
            PLAYER_ONLY to "<red>此操作只能由玩家执行</red>",
            COMMAND_NO_PERMISSION to "<red>你没有权限执行此命令</red>",
            COMMAND_PLAYER_ONLY to "<red>此命令只能由玩家执行</red>",
            COMMAND_INCOMPLETE to "<red>命令不完整</red>",
            COMMAND_USAGE to "<yellow>用法: {usage}</yellow>",
            COMMAND_INTERNAL_ERROR to "<red>命令执行出错，请联系管理员（详见控制台）</red>",
            UNKNOWN_ARGUMENT to "<red>未知参数：{argument}</red>",
            SUGGESTION to "<gray>你是不是想输入 {suggestion}？</gray>",
            HELP_HEADER to "<gold>/{command} 帮助 {page}/{pages}</gold>",
            HELP_PREVIOUS to "<gold>[上一页]</gold>",
            HELP_NEXT to "<gold>[下一页]</gold>",
            HELP_OPEN_PAGE to "<gold>打开第 {page} 页</gold>",
            ARG_INTEGER to "<red>需要整数</red>",
            ARG_INTEGER_RANGE to "<red>整数范围必须为 {minimum}..{maximum}</red>",
            ARG_DECIMAL to "<red>需要小数</red>",
            ARG_DECIMAL_RANGE to "<red>小数范围必须为 {range}</red>",
            ARG_BOOLEAN to "<red>需要 true/false、yes/no、on/off 或 1/0</red>",
            ARG_CHOICE to "<red>可选值: {choices}</red>",
            ARG_PLAYER_OFFLINE to "<red>玩家不在线: {player}</red>",
            ARG_CONTENT_EMPTY to "<red>内容不能为空</red>",
            BUILTIN_HELP_DESCRIPTION to "<gold>查看命令帮助</gold>",
            BUILTIN_RELOAD_DESCRIPTION to "<gold>重新加载插件配置</gold>",
            BUILTIN_RELOAD_SUCCESS to "<green>重新加载完成</green>",
            BUILTIN_RELOAD_FAILURE to "<red>重新加载失败: {reason}</red>",
            BUILTIN_DEBUG_DESCRIPTION to "<gold>切换调试模式</gold>",
            BUILTIN_DEBUG_ENABLED to "<green>调试模式已开启</green>",
            BUILTIN_DEBUG_DISABLED to "<yellow>调试模式已关闭</yellow>"
        )
        private fun englishValues(): Map<String, Any> = linkedMapOf(
            NO_PERMISSION to "<red>You do not have permission to do this</red>",
            PLAYER_ONLY to "<red>Only players can do this</red>",
            COMMAND_NO_PERMISSION to "<red>You do not have permission to run this command</red>",
            COMMAND_PLAYER_ONLY to "<red>Only players can run this command</red>",
            COMMAND_INCOMPLETE to "<red>Incomplete command</red>",
            COMMAND_USAGE to "<yellow>Usage: {usage}</yellow>",
            COMMAND_INTERNAL_ERROR to "<red>The command failed; contact an administrator and check the console</red>",
            UNKNOWN_ARGUMENT to "<red>Unknown argument: {argument}</red>",
            SUGGESTION to "<gray>Did you mean {suggestion}?</gray>",
            HELP_HEADER to "<gold>/{command} help {page}/{pages}</gold>",
            HELP_PREVIOUS to "<gold>[Previous]</gold>",
            HELP_NEXT to "<gold>[Next]</gold>",
            HELP_OPEN_PAGE to "<gold>Open page {page}</gold>",
            ARG_INTEGER to "<red>An integer is required</red>",
            ARG_INTEGER_RANGE to "<red>The integer must be within {minimum}..{maximum}</red>",
            ARG_DECIMAL to "<red>A decimal number is required</red>",
            ARG_DECIMAL_RANGE to "<red>The decimal number must be within {range}</red>",
            ARG_BOOLEAN to "<red>true/false, yes/no, on/off or 1/0 is required</red>",
            ARG_CHOICE to "<red>Allowed values: {choices}</red>",
            ARG_PLAYER_OFFLINE to "<red>Player is offline: {player}</red>",
            ARG_CONTENT_EMPTY to "<red>Content must not be empty</red>",
            BUILTIN_HELP_DESCRIPTION to "<gold>Show command help</gold>",
            BUILTIN_RELOAD_DESCRIPTION to "<gold>Reload the plugin configuration</gold>",
            BUILTIN_RELOAD_SUCCESS to "<green>Reload complete</green>",
            BUILTIN_RELOAD_FAILURE to "<red>Reload failed: {reason}</red>",
            BUILTIN_DEBUG_DESCRIPTION to "<gold>Toggle debug mode</gold>",
            BUILTIN_DEBUG_ENABLED to "<green>Debug mode enabled</green>",
            BUILTIN_DEBUG_DISABLED to "<yellow>Debug mode disabled</yellow>"
        )
    }
}
