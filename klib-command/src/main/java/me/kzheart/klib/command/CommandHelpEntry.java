package me.kzheart.klib.command;

import java.util.Objects;

/** 平铺帮助条目，例如一个业务插件汇总多个独立短命令；调用方先过滤权限。 */
public final class CommandHelpEntry {
    private final String usage, description, category, hover;
    public CommandHelpEntry(String usage, String description) { this(usage, description, "", ""); }
    public CommandHelpEntry(String usage, String description, String category, String hover) {
        this.usage = Objects.requireNonNull(usage, "usage");
        this.description = Objects.requireNonNull(description, "description");
        this.category = Objects.requireNonNull(category, "category");
        this.hover = Objects.requireNonNull(hover, "hover");
    }
    public String usage() { return usage; }
    public String description() { return description; }
    public String category() { return category; }
    public String hover() { return hover; }
}
