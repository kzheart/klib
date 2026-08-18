package me.kzheart.klib.lang;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 插件提供覆盖项之前，各模块可用的通用消息键。 */
public final class BuiltinMessages {
    public static final String NO_PERMISSION = "common.no-permission";
    public static final String PLAYER_ONLY = "common.player-only";
    public static final String COMMAND_NO_PERMISSION = "command.no-permission";
    public static final String COMMAND_PLAYER_ONLY = "command.player-only";
    public static final String COMMAND_INCOMPLETE = "command.incomplete";
    public static final String COMMAND_USAGE = "command.usage";
    public static final String COMMAND_INTERNAL_ERROR = "command.internal-error";
    public static final String UNKNOWN_ARGUMENT = "command.unknown-argument";
    public static final String SUGGESTION = "command.suggestion";
    public static final String HELP_HEADER = "command.help.header";
    public static final String HELP_PREVIOUS = "command.help.previous";
    public static final String HELP_NEXT = "command.help.next";
    public static final String HELP_OPEN_PAGE = "command.help.open-page";
    public static final String ARG_INTEGER = "command.argument.integer";
    public static final String ARG_INTEGER_RANGE = "command.argument.integer-range";
    public static final String ARG_DECIMAL = "command.argument.decimal";
    public static final String ARG_DECIMAL_RANGE = "command.argument.decimal-range";
    public static final String ARG_BOOLEAN = "command.argument.boolean";
    public static final String ARG_CHOICE = "command.argument.choice";
    public static final String ARG_PLAYER_OFFLINE = "command.argument.player-offline";
    public static final String ARG_CONTENT_EMPTY = "command.argument.content-empty";
    public static final String BUILTIN_HELP_DESCRIPTION = "command.builtin.help.description";
    public static final String BUILTIN_RELOAD_DESCRIPTION = "command.builtin.reload.description";
    public static final String BUILTIN_RELOAD_SUCCESS = "command.builtin.reload.success";
    public static final String BUILTIN_RELOAD_FAILURE = "command.builtin.reload.failure";
    public static final String BUILTIN_DEBUG_DESCRIPTION = "command.builtin.debug.description";
    public static final String BUILTIN_DEBUG_ENABLED = "command.builtin.debug.enabled";
    public static final String BUILTIN_DEBUG_DISABLED = "command.builtin.debug.disabled";

    private BuiltinMessages() {
    }

    public static MessageCatalog catalog() {
        return catalog(null);
    }

    /** 感知地区的默认值：{@code en*} 地区使用英文，其余地区使用中文。 */
    public static MessageCatalog catalog(String locale) {
        return new MapMessageCatalog(isEnglish(locale) ? englishValues() : chineseValues());
    }

    private static boolean isEnglish(String locale) {
        return locale != null && locale.trim().toLowerCase(Locale.ROOT).startsWith("en");
    }

    private static Map<String, Object> chineseValues() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(NO_PERMISSION, "<red>你没有权限执行此操作</red>");
        values.put(PLAYER_ONLY, "<red>此操作只能由玩家执行</red>");
        values.put(COMMAND_NO_PERMISSION, "<red>你没有权限执行此命令</red>");
        values.put(COMMAND_PLAYER_ONLY, "<red>此命令只能由玩家执行</red>");
        values.put(COMMAND_INCOMPLETE, "<red>命令不完整</red>");
        values.put(COMMAND_USAGE, "<yellow>用法: {usage}</yellow>");
        values.put(COMMAND_INTERNAL_ERROR, "<red>命令执行出错，请联系管理员（详见控制台）</red>");
        values.put(UNKNOWN_ARGUMENT, "<red>未知参数：{argument}</red>");
        values.put(SUGGESTION, "<gray>你是不是想输入 {suggestion}？</gray>");
        values.put(HELP_HEADER, "<gold>/{command} 帮助 {page}/{pages}</gold>");
        values.put(HELP_PREVIOUS, "<gold>[上一页]</gold>");
        values.put(HELP_NEXT, "<gold>[下一页]</gold>");
        values.put(HELP_OPEN_PAGE, "<gold>打开第 {page} 页</gold>");
        values.put(ARG_INTEGER, "<red>需要整数</red>");
        values.put(ARG_INTEGER_RANGE, "<red>整数范围必须为 {minimum}..{maximum}</red>");
        values.put(ARG_DECIMAL, "<red>需要小数</red>");
        values.put(ARG_DECIMAL_RANGE, "<red>小数范围必须为 {range}</red>");
        values.put(ARG_BOOLEAN, "<red>需要 true/false、yes/no、on/off 或 1/0</red>");
        values.put(ARG_CHOICE, "<red>可选值: {choices}</red>");
        values.put(ARG_PLAYER_OFFLINE, "<red>玩家不在线: {player}</red>");
        values.put(ARG_CONTENT_EMPTY, "<red>内容不能为空</red>");
        values.put(BUILTIN_HELP_DESCRIPTION, "<gold>查看命令帮助</gold>");
        values.put(BUILTIN_RELOAD_DESCRIPTION, "<gold>重新加载插件配置</gold>");
        values.put(BUILTIN_RELOAD_SUCCESS, "<green>重新加载完成</green>");
        values.put(BUILTIN_RELOAD_FAILURE, "<red>重新加载失败: {reason}</red>");
        values.put(BUILTIN_DEBUG_DESCRIPTION, "<gold>切换调试模式</gold>");
        values.put(BUILTIN_DEBUG_ENABLED, "<green>调试模式已开启</green>");
        values.put(BUILTIN_DEBUG_DISABLED, "<yellow>调试模式已关闭</yellow>");
        return values;
    }

    private static Map<String, Object> englishValues() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put(NO_PERMISSION, "<red>You do not have permission to do this</red>");
        values.put(PLAYER_ONLY, "<red>Only players can do this</red>");
        values.put(COMMAND_NO_PERMISSION, "<red>You do not have permission to run this command</red>");
        values.put(COMMAND_PLAYER_ONLY, "<red>Only players can run this command</red>");
        values.put(COMMAND_INCOMPLETE, "<red>Incomplete command</red>");
        values.put(COMMAND_USAGE, "<yellow>Usage: {usage}</yellow>");
        values.put(COMMAND_INTERNAL_ERROR,
                "<red>The command failed; contact an administrator and check the console</red>");
        values.put(UNKNOWN_ARGUMENT, "<red>Unknown argument: {argument}</red>");
        values.put(SUGGESTION, "<gray>Did you mean {suggestion}?</gray>");
        values.put(HELP_HEADER, "<gold>/{command} help {page}/{pages}</gold>");
        values.put(HELP_PREVIOUS, "<gold>[Previous]</gold>");
        values.put(HELP_NEXT, "<gold>[Next]</gold>");
        values.put(HELP_OPEN_PAGE, "<gold>Open page {page}</gold>");
        values.put(ARG_INTEGER, "<red>An integer is required</red>");
        values.put(ARG_INTEGER_RANGE, "<red>The integer must be within {minimum}..{maximum}</red>");
        values.put(ARG_DECIMAL, "<red>A decimal number is required</red>");
        values.put(ARG_DECIMAL_RANGE, "<red>The decimal number must be within {range}</red>");
        values.put(ARG_BOOLEAN, "<red>true/false, yes/no, on/off or 1/0 is required</red>");
        values.put(ARG_CHOICE, "<red>Allowed values: {choices}</red>");
        values.put(ARG_PLAYER_OFFLINE, "<red>Player is offline: {player}</red>");
        values.put(ARG_CONTENT_EMPTY, "<red>Content must not be empty</red>");
        values.put(BUILTIN_HELP_DESCRIPTION, "<gold>Show command help</gold>");
        values.put(BUILTIN_RELOAD_DESCRIPTION, "<gold>Reload the plugin configuration</gold>");
        values.put(BUILTIN_RELOAD_SUCCESS, "<green>Reload complete</green>");
        values.put(BUILTIN_RELOAD_FAILURE, "<red>Reload failed: {reason}</red>");
        values.put(BUILTIN_DEBUG_DESCRIPTION, "<gold>Toggle debug mode</gold>");
        values.put(BUILTIN_DEBUG_ENABLED, "<green>Debug mode enabled</green>");
        values.put(BUILTIN_DEBUG_DISABLED, "<yellow>Debug mode disabled</yellow>");
        return values;
    }
}
