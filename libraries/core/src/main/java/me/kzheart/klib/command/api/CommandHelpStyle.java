package me.kzheart.klib.command.api;

/** Java 平台返回契约；模板、校验和快照由 Kotlin 值对象实现。
 * 旧 Kotlin 调用 if (style.header() != null) style.header().length 依赖平台类型；CLASSIC 仍可返回 null。
 */
public final class CommandHelpStyle {
    public enum Preset { ELEGANT, COMPACT, PANEL, CLASSIC }
    private final CommandHelpStyleValue state;
    private CommandHelpStyle(CommandHelpStyleValue state) { this.state = state; }
    public static CommandHelpStyle preset(Preset preset) { return new CommandHelpStyle(CommandHelpStyleValue.preset(preset)); }
    public static Builder builder(Preset preset) { return new Builder(CommandHelpStyleValue.builder(preset)); }
    public Builder toBuilder() { return new Builder(state.toBuilder()); }
    public String header() { return state.header(); }
    public String entry() { return state.entry(); }
    public String description() { return state.description(); }
    public String usage() { return state.usage(); }
    public String previous() { return state.previous(); }
    public String next() { return state.next(); }
    public String footer() { return state.footer(); }
    public String hover() { return state.hover(); }
    public String group() { return state.group(); }
    public int pageSize() { return state.pageSize(); }
    public int clearLines() { return state.clearLines(); }
    public int commandColor() { return state.commandColor(); }
    public int literalColor() { return state.literalColor(); }
    public int argumentColor() { return state.argumentColor(); }

    public static final class Builder {
        private final CommandHelpStyleValue.Builder state;
        private Builder(CommandHelpStyleValue.Builder state) { this.state = state; }
        public Builder header(String value) { state.header(value); return this; }
        public Builder entry(String value) { state.entry(value); return this; }
        public Builder description(String value) { state.description(value); return this; }
        public Builder usage(String value) { state.usage(value); return this; }
        public Builder previous(String value) { state.previous(value); return this; }
        public Builder next(String value) { state.next(value); return this; }
        public Builder footer(String value) { state.footer(value); return this; }
        public Builder hover(String value) { state.hover(value); return this; }
        public Builder group(String value) { state.group(value); return this; }
        public Builder pageSize(int value) { state.pageSize(value); return this; }
        public Builder clearLines(int value) { state.clearLines(value); return this; }
        public Builder commandColor(int value) { state.commandColor(value); return this; }
        public Builder literalColor(int value) { state.literalColor(value); return this; }
        public Builder argumentColor(int value) { state.argumentColor(value); return this; }
        public CommandHelpStyle build() { return new CommandHelpStyle(state.build()); }
    }
}
