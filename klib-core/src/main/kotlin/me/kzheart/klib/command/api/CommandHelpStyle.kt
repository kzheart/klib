package me.kzheart.klib.command.api

/** Platform-independent immutable layout; builders validate all template and numeric constraints. */
class CommandHelpStyle private constructor(private val values: Values) {
    enum class Preset { ELEGANT, COMPACT, PANEL, CLASSIC }
    fun toBuilder(): Builder = Builder.copyOf(this)
    fun header(): String? = values.header
    fun entry(): String = values.entry
    fun description(): String = values.description
    fun usage(): String = values.usage
    fun previous(): String? = values.previous
    fun next(): String? = values.next
    fun footer(): String = values.footer
    fun hover(): String = values.hover
    fun group(): String = values.group
    fun pageSize(): Int = values.pageSize
    fun clearLines(): Int = values.clearLines
    fun commandColor(): Int = values.commandColor
    fun literalColor(): Int = values.literalColor
    fun argumentColor(): Int = values.argumentColor

    // Snapshotting this private value set keeps builders independent from already published styles.
    private data class Values(
        var header: String? = "<dark_gray>───── <#e8b04a><bold>/{command}</bold> <#d9c9aa>命令指南 <dark_gray>─────\n<#9e988c>{summary}",
        var entry: String = "  <#e8b04a>› {usage} <dark_gray>— <#c3bba8>{description}",
        var description: String = "",
        var usage: String = "<#e8b04a>用法 <dark_gray>› {usage}\n<#a8a293>用途 <dark_gray>› <#cfc5b2>{description}",
        var previous: String? = "<#a8a293>[ ← 上一页 ]",
        var next: String? = "<#e8b04a>[ 下一页 → ]",
        var footer: String = "<dark_gray>第 <#e8b04a>{page}<dark_gray> / {pages} 页 · 点击命令预填，悬停查看用途",
        var hover: String = "{description}\n用法：{usage}\n点击填入聊天输入框",
        var group: String = "<dark_gray>── <#d9c9aa>{category} <dark_gray>──",
        var pageSize: Int = 7,
        var clearLines: Int = 0,
        var commandColor: Int = 0xFFAA00,
        var literalColor: Int = 0xFFFF55,
        var argumentColor: Int = 0xAAAAAA,
    )
    class Builder private constructor(private val values: Values) {
        companion object {
            @JvmSynthetic internal fun copyOf(style: CommandHelpStyle): Builder = Builder(style.values.copy())
            @JvmSynthetic internal fun create(preset: Preset): Builder = Builder(presetValues(preset))
        }
        fun header(value: String): Builder = apply { values.header = value }
        fun entry(value: String): Builder = apply { values.entry = value }
        fun description(value: String): Builder = apply { values.description = value }
        fun usage(value: String): Builder = apply { values.usage = value }
        fun previous(value: String): Builder = apply { values.previous = value }
        fun next(value: String): Builder = apply { values.next = value }
        fun footer(value: String): Builder = apply { values.footer = value }
        fun hover(value: String): Builder = apply { values.hover = value }
        fun group(value: String): Builder = apply { values.group = value }
        fun pageSize(value: Int): Builder = apply { require(value in 1..20) { "pageSize must be 1..20" }; values.pageSize = value }
        fun clearLines(value: Int): Builder = apply { require(value in 0..100) { "clearLines must be 0..100" }; values.clearLines = value }
        fun commandColor(rgb: Int): Builder = apply { values.commandColor = color(rgb) }
        fun literalColor(rgb: Int): Builder = apply { values.literalColor = color(rgb) }
        fun argumentColor(rgb: Int): Builder = apply { values.argumentColor = color(rgb) }
        fun build(): CommandHelpStyle {
            require("{usage}" in values.entry && "{description}" in values.entry + values.description) { "help entry must include usage and description" }
            require("{usage}" in values.usage && "{description}" in values.usage) { "incomplete usage must include usage and description" }
            return CommandHelpStyle(values.copy())
        }
        private fun color(rgb: Int): Int = rgb.also { require(it in 0..0xFFFFFF) { "RGB must be 0..0xFFFFFF" } }
    }
    companion object {
        @JvmStatic fun preset(preset: Preset): CommandHelpStyle = builder(preset).build()
        @JvmStatic fun builder(preset: Preset): Builder = Builder.create(preset)
        private fun presetValues(preset: Preset): Values = Values().apply {
            when (preset) {
                Preset.ELEGANT -> Unit
                Preset.COMPACT -> {
                    header = "<#e8b04a>/{command} <#ddd3c0>帮助 <dark_gray>· <#aaa393>{page}/{pages}"
                    entry = "{usage} <dark_gray>› <#b9b3a6>{description}"
                    footer = "<dark_gray>点击预填 · 悬停查看用法"
                }
                Preset.PANEL -> {
                    header = "<dark_gray>┌─ <#e8b04a><bold>/{command}</bold> <#d9c9aa>操作面板\n<dark_gray>│ <#9e988c>{summary}"
                    entry = "<dark_gray>│  {usage}"
                    description = "<dark_gray>│    <#b9b3a6>{description}"
                    footer = "<dark_gray>└─ <#e8b04a>{page}<dark_gray> / {pages} · 点击预填命令"
                    pageSize = 5
                }
                Preset.CLASSIC -> {
                    header = null; previous = null; next = null
                    entry = "{usage} <dark_gray>- <gray>{description}"
                    footer = ""
                }
            }
        }
    }
}
