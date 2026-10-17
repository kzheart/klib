package me.kzheart.klib.command

import java.util.Locale
import me.kzheart.klib.command.api.CommandHelpStyle
import org.bukkit.configuration.ConfigurationSection

class CommandHelpStyles private constructor() {
    companion object {
        @JvmStatic fun parse(section: ConfigurationSection?): CommandHelpStyle {
            if (section == null) return CommandHelpStyle.preset(CommandHelpStyle.Preset.ELEGANT)
            val value = requireNotNull(section.getString("preset", "elegant")).uppercase(Locale.ROOT)
            val custom = value == "CUSTOM"
            val preset = try { if (custom) CommandHelpStyle.Preset.ELEGANT else CommandHelpStyle.Preset.valueOf(value) }
                catch (failure: IllegalArgumentException) { throw IllegalArgumentException("help.preset 应为 elegant/compact/panel/classic/custom", failure) }
            val builder = CommandHelpStyle.builder(preset)
            if (section.contains("page-size")) builder.pageSize(section.getInt("page-size"))
            if (section.contains("clear-lines")) builder.clearLines(section.getInt("clear-lines"))
            val overrides = section.getConfigurationSection("custom")
            require(!custom || overrides != null) { "help.custom 缺少自定义布局" }
            if (custom && overrides != null) {
                val textOptions = mapOf<String, (String) -> CommandHelpStyle.Builder>(
                    "header" to builder::header, "entry" to builder::entry, "description" to builder::description,
                    "usage" to builder::usage, "previous" to builder::previous, "next" to builder::next,
                    "footer" to builder::footer, "hover" to builder::hover, "group" to builder::group)
                textOptions.forEach { (key, apply) -> if (overrides.contains(key)) apply(requireNotNull(overrides.getString(key, ""))) }
                val colorOptions = mapOf<String, (Int) -> CommandHelpStyle.Builder>("command-color" to builder::commandColor,
                    "literal-color" to builder::literalColor, "argument-color" to builder::argumentColor)
                colorOptions.forEach { (key, apply) -> if (overrides.contains(key)) apply(color(overrides.getString(key))) }
            }
            return builder.build()
        }
        private fun color(value: String?): Int {
            require(value != null && Regex("#[0-9a-fA-F]{6}").matches(value)) { "help 颜色应为 #RRGGBB" }
            return value.substring(1).toInt(16)
        }
    }
}
