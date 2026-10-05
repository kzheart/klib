package me.kzheart.klib.command;

import java.util.Locale;
import me.kzheart.klib.command.api.CommandHelpStyle;
import org.bukkit.configuration.ConfigurationSection;

/** 读取业务插件自己持有的 YAML 节；不创建配置文件、不重写配置、不注册命令。 */
public final class CommandHelpStyles {
    private CommandHelpStyles() { }
    public static CommandHelpStyle parse(ConfigurationSection section) {
        if (section == null) return CommandHelpStyle.preset(CommandHelpStyle.Preset.ELEGANT);
        String value = section.getString("preset", "elegant").toUpperCase(Locale.ROOT);
        boolean custom = value.equals("CUSTOM");
        CommandHelpStyle.Preset preset;
        try { preset = custom ? CommandHelpStyle.Preset.ELEGANT : CommandHelpStyle.Preset.valueOf(value); }
        catch (IllegalArgumentException failure) { throw new IllegalArgumentException("help.preset 应为 elegant/compact/panel/classic/custom", failure); }
        CommandHelpStyle.Builder builder = CommandHelpStyle.builder(preset);
        if (section.contains("page-size")) builder.pageSize(section.getInt("page-size"));
        if (section.contains("clear-lines")) builder.clearLines(section.getInt("clear-lines"));
        ConfigurationSection overrides = section.getConfigurationSection("custom");
        if (custom && overrides == null) throw new IllegalArgumentException("help.custom 缺少自定义布局");
        if (custom && overrides != null) {
            if (overrides.contains("header")) builder.header(overrides.getString("header", ""));
            if (overrides.contains("entry")) builder.entry(overrides.getString("entry", ""));
            if (overrides.contains("description")) builder.description(overrides.getString("description", ""));
            if (overrides.contains("usage")) builder.usage(overrides.getString("usage", ""));
            if (overrides.contains("previous")) builder.previous(overrides.getString("previous", ""));
            if (overrides.contains("next")) builder.next(overrides.getString("next", ""));
            if (overrides.contains("footer")) builder.footer(overrides.getString("footer", ""));
            if (overrides.contains("hover")) builder.hover(overrides.getString("hover", ""));
            if (overrides.contains("group")) builder.group(overrides.getString("group", ""));
            if (overrides.contains("command-color")) builder.commandColor(color(overrides.getString("command-color")));
            if (overrides.contains("literal-color")) builder.literalColor(color(overrides.getString("literal-color")));
            if (overrides.contains("argument-color")) builder.argumentColor(color(overrides.getString("argument-color")));
        }
        return builder.build();
    }
    private static int color(String value) {
        if (value == null || !value.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("help 颜色应为 #RRGGBB");
        return Integer.parseInt(value.substring(1), 16);
    }
}
