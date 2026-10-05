package me.kzheart.klib.command.api;

import java.util.Objects;

/** 不依赖平台富文本类型的帮助布局；模板使用 MiniMessage，参数与说明以字面值插入。 */
public final class CommandHelpStyle {
    public enum Preset { ELEGANT, COMPACT, PANEL, CLASSIC }
    private final String header, entry, description, usage, previous, next, footer, hover, group;
    private final int pageSize, clearLines, commandColor, literalColor, argumentColor;
    private CommandHelpStyle(Builder b) {
        header = b.header; entry = b.entry; description = b.description; usage = b.usage;
        previous = b.previous; next = b.next; footer = b.footer; hover = b.hover;
        group = b.group;
        pageSize = b.pageSize; clearLines = b.clearLines;
        commandColor = b.commandColor; literalColor = b.literalColor; argumentColor = b.argumentColor;
    }
    public static CommandHelpStyle preset(Preset preset) { return builder(preset).build(); }
    public static Builder builder(Preset preset) { return new Builder(preset); }
    public Builder toBuilder() {
        Builder b = builder(Preset.ELEGANT);
        b.header = header; b.entry = entry; b.description = description; b.usage = usage;
        b.previous = previous; b.next = next; b.footer = footer; b.hover = hover; b.group = group;
        b.pageSize = pageSize; b.clearLines = clearLines; b.commandColor = commandColor;
        b.literalColor = literalColor; b.argumentColor = argumentColor;
        return b;
    }
    public String header() { return header; }
    public String entry() { return entry; }
    public String description() { return description; }
    public String usage() { return usage; }
    public String previous() { return previous; }
    public String next() { return next; }
    public String footer() { return footer; }
    public String hover() { return hover; }
    public String group() { return group; }
    public int pageSize() { return pageSize; }
    public int clearLines() { return clearLines; }
    public int commandColor() { return commandColor; }
    public int literalColor() { return literalColor; }
    public int argumentColor() { return argumentColor; }

    public static final class Builder {
        private String header, entry, description = "", usage, previous, next, footer, hover;
        private String group = "<dark_gray>── <#d9c9aa>{category} <dark_gray>──";
        private int pageSize = 7, clearLines;
        private int commandColor = 0xFFAA00, literalColor = 0xFFFF55, argumentColor = 0xAAAAAA;
        private Builder(Preset preset) {
            Objects.requireNonNull(preset, "preset");
            previous = "<#a8a293>[ ← 上一页 ]";
            next = "<#e8b04a>[ 下一页 → ]";
            footer = "<dark_gray>第 <#e8b04a>{page}<dark_gray> / {pages} 页 · 点击命令预填，悬停查看用途";
            hover = "{description}\n用法：{usage}\n点击填入聊天输入框";
            usage = "<#e8b04a>用法 <dark_gray>› {usage}\n<#a8a293>用途 <dark_gray>› <#cfc5b2>{description}";
            switch (preset) {
                case ELEGANT:
                    header = "<dark_gray>───── <#e8b04a><bold>/{command}</bold> <#d9c9aa>命令指南 <dark_gray>─────\n<#9e988c>{summary}";
                    entry = "  <#e8b04a>› {usage} <dark_gray>— <#c3bba8>{description}";
                    break;
                case COMPACT:
                    header = "<#e8b04a>/{command} <#ddd3c0>帮助 <dark_gray>· <#aaa393>{page}/{pages}";
                    entry = "{usage} <dark_gray>› <#b9b3a6>{description}";
                    footer = "<dark_gray>点击预填 · 悬停查看用法";
                    break;
                case PANEL:
                    header = "<dark_gray>┌─ <#e8b04a><bold>/{command}</bold> <#d9c9aa>操作面板\n<dark_gray>│ <#9e988c>{summary}";
                    entry = "<dark_gray>│  {usage}";
                    description = "<dark_gray>│    <#b9b3a6>{description}";
                    footer = "<dark_gray>└─ <#e8b04a>{page}<dark_gray> / {pages} · 点击预填命令";
                    pageSize = 5;
                    break;
                case CLASSIC:
                    // null 表示复用原有 CommandMessages 的标题/分页文案。
                    header = null; previous = null; next = null;
                    entry = "{usage} <dark_gray>- <gray>{description}";
                    footer = "";
                    break;
                default: throw new IllegalArgumentException("unknown preset");
            }
        }
        public Builder header(String value) { header = Objects.requireNonNull(value, "header"); return this; }
        public Builder entry(String value) { entry = Objects.requireNonNull(value, "entry"); return this; }
        public Builder description(String value) { description = Objects.requireNonNull(value, "description"); return this; }
        public Builder usage(String value) { usage = Objects.requireNonNull(value, "usage"); return this; }
        public Builder previous(String value) { previous = Objects.requireNonNull(value, "previous"); return this; }
        public Builder next(String value) { next = Objects.requireNonNull(value, "next"); return this; }
        public Builder footer(String value) { footer = Objects.requireNonNull(value, "footer"); return this; }
        public Builder hover(String value) { hover = Objects.requireNonNull(value, "hover"); return this; }
        public Builder group(String value) { group = Objects.requireNonNull(value, "group"); return this; }
        public Builder pageSize(int value) {
            if (value < 1 || value > 20) throw new IllegalArgumentException("pageSize must be 1..20");
            pageSize = value; return this;
        }
        public Builder clearLines(int value) {
            if (value < 0 || value > 100) throw new IllegalArgumentException("clearLines must be 0..100");
            clearLines = value; return this;
        }
        public Builder commandColor(int rgb) { commandColor = color(rgb); return this; }
        public Builder literalColor(int rgb) { literalColor = color(rgb); return this; }
        public Builder argumentColor(int rgb) { argumentColor = color(rgb); return this; }
        private static int color(int rgb) {
            if (rgb < 0 || rgb > 0xFFFFFF) throw new IllegalArgumentException("RGB must be 0..0xFFFFFF");
            return rgb;
        }
        public CommandHelpStyle build() {
            if (!entry.contains("{usage}") || !(entry + description).contains("{description}"))
                throw new IllegalArgumentException("help entry must include usage and description");
            if (!usage.contains("{usage}") || !usage.contains("{description}"))
                throw new IllegalArgumentException("incomplete usage must include usage and description");
            return new CommandHelpStyle(this);
        }
    }
}
