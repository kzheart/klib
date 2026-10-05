package me.kzheart.klib.command;

import me.kzheart.klib.command.api.CommandHelpStyle;
import me.kzheart.klib.command.api.CommandSpec;
import me.kzheart.klib.lang.MessageColor;
import me.kzheart.klib.lang.MessagePipeline;
import me.kzheart.klib.lang.MessageRecipient;
import me.kzheart.klib.lang.RichText;
import me.kzheart.klib.lang.RichTextSegment;
import me.kzheart.klib.lang.TextAction;
import org.bukkit.command.CommandSender;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;

/** 帮助布局与行为分离；所有预设和自定义模板都保留预填、悬停及分页。 */
public final class HelpRenderer {
    private static final CommandHelpStyle DEFAULT = CommandHelpStyle.preset(CommandHelpStyle.Preset.ELEGANT);
    private final CommandMessages messages;
    public HelpRenderer(CommandMessages messages) { this.messages = Objects.requireNonNull(messages, "messages"); }

    static CommandHelpStyle style(CommandNode node) {
        for (CommandNode current = node; current != null; current = current.parent)
            if (current.helpStyle != null) return Objects.requireNonNull(current.helpStyle.get(), "help style provider returned null");
        return DEFAULT;
    }

    public HelpPage render(CommandSpec spec, CommandSender sender, int requestedPage, int pageSize) {
        if (!(Objects.requireNonNull(spec, "spec") instanceof CommandSpecImpl)) throw new IllegalArgumentException("命令规格必须由 Klib 创建");
        Objects.requireNonNull(sender, "sender");
        if (pageSize < 1) throw new IllegalArgumentException("pageSize must be positive");
        CommandSpecImpl typed = (CommandSpecImpl) spec;
        CommandHelpStyle style = style(typed.root());
        List<HelpEntry> entries = new ArrayList<HelpEntry>();
        if (CommandDispatcher.isAccessible(sender, typed.root()))
            collect(typed.root(), sender, "/" + typed.name(), new HashSet<String>(), entries);
        return renderEntries(sender, typed.name(), description(typed.root(), sender), entries, requestedPage, pageSize, style,
                page -> "/" + typed.name() + " help " + page);
    }

    /** 汇总独立短命令；navigation 必须返回调用方实际注册的翻页命令。 */
    public HelpPage renderEntries(CommandSender sender, String command, String summary, List<CommandHelpEntry> entries,
                                  int page, CommandHelpStyle style, IntFunction<String> navigation) {
        Objects.requireNonNull(sender, "sender"); Objects.requireNonNull(command, "command");
        Objects.requireNonNull(summary, "summary"); Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(style, "style"); Objects.requireNonNull(navigation, "navigation");
        List<HelpEntry> flat = new ArrayList<HelpEntry>();
        for (CommandHelpEntry entry : entries) flat.add(new HelpEntry(entry.usage(),
                entry.description().isEmpty() ? "暂无说明" : entry.description(), suggestion(entry.usage()), entry.category(), entry.hover()));
        return renderEntries(sender, command, summary, flat, page, style.pageSize(), style, navigation);
    }

    private HelpPage renderEntries(CommandSender sender, String command, String summary, List<HelpEntry> entries,
                                    int requestedPage, int pageSize, CommandHelpStyle style, IntFunction<String> navigation) {
        int pages = Math.max(1, (entries.size() + pageSize - 1) / pageSize);
        int page = Math.max(1, Math.min(requestedPage, pages));
        int from = Math.min((page - 1) * pageSize, entries.size());
        Map<String, Object> values = MessagePlaceholders.of("command", command, "page", Integer.valueOf(page),
                "pages", Integer.valueOf(pages), "count", Integer.valueOf(entries.size()), "summary", summary);
        List<RichTextSegment> output = new ArrayList<RichTextSegment>();
        for (int i = 0; i < style.clearLines(); i++) output.add(RichTextSegment.plain("\n"));
        output.addAll((style.header() == null ? messages.resolve(sender, CommandMessageKeys.HELP_HEADER, values)
                : template(style.header(), sender, values)).segments());
        String category = "";
        for (int i = from; i < Math.min(from + pageSize, entries.size()); i++) {
            HelpEntry entry = entries.get(i);
            if (!entry.category.isEmpty() && !entry.category.equals(category)) {
                category = entry.category;
                output.add(RichTextSegment.plain("\n"));
                output.addAll(template(style.group(), sender, MessagePlaceholders.of("category", category)).segments());
            }
            output.add(RichTextSegment.plain("\n"));
            appendEntry(output, sender, style, style.entry(), entry, i + 1);
            if (!style.description().isEmpty()) {
                output.add(RichTextSegment.plain("\n"));
                appendEntry(output, sender, style, style.description(), entry, i + 1);
            }
        }
        if (entries.isEmpty()) output.addAll(template("\n<gray>没有可使用的命令", sender, values).segments());
        if (pages > 1) {
            output.add(RichTextSegment.plain("\n"));
            if (page > 1) output.addAll(navigation(style.previous(), CommandMessageKeys.HELP_PREVIOUS, navigation.apply(page - 1), page - 1, sender, values));
            if (page > 1 && page < pages) output.add(RichTextSegment.plain("  "));
            if (page < pages) output.addAll(navigation(style.next(), CommandMessageKeys.HELP_NEXT, navigation.apply(page + 1), page + 1, sender, values));
        }
        if (!style.footer().isEmpty()) {
            output.add(RichTextSegment.plain("\n"));
            output.addAll(template(style.footer(), sender, values).segments());
        }
        return new HelpPage(page, pages, new RichText(output));
    }

    /** 参数不完整时列出完整可达语法并说明用途，描述从最近的有说明祖先继承。 */
    RichText usage(CommandNode node, CommandSender sender, String prefix, int maxLines) {
        List<HelpEntry> entries = new ArrayList<HelpEntry>();
        collect(node, sender, prefix, new HashSet<String>(), entries);
        if (entries.isEmpty() || entries.size() > maxLines) return null;
        CommandHelpStyle style = style(node);
        List<RichTextSegment> output = new ArrayList<RichTextSegment>();
        for (HelpEntry entry : entries) {
            if (!output.isEmpty()) output.add(RichTextSegment.plain("\n"));
            appendEntry(output, sender, style, style.usage(), entry, 1);
        }
        return new RichText(output);
    }

    private void appendEntry(List<RichTextSegment> output, CommandSender sender, CommandHelpStyle style,
                              String layout, HelpEntry entry, int index) {
        Map<String, Object> values = MessagePlaceholders.of("description", entry.description, "index", Integer.valueOf(index));
        RichText parsed = template(layout, sender, values);
        List<RichTextSegment> line = new ArrayList<RichTextSegment>();
        for (RichTextSegment part : parsed.segments()) {
            String text = part.text();
            int offset = 0, found;
            while ((found = text.indexOf("{usage}", offset)) >= 0) {
                if (found > offset) line.add(withText(part, text.substring(offset, found)));
                appendStyledUsage(line, entry.usage, style);
                offset = found + 7;
            }
            if (offset < text.length()) line.add(withText(part, text.substring(offset)));
        }
        String hover = entry.hover.isEmpty() ? template(style.hover(), sender, MessagePlaceholders.of("description", entry.description, "usage", entry.usage)).plainText() : entry.hover;
        CommandDispatcher.appendWithActions(output, new RichText(line), new TextAction(TextAction.Type.HOVER_TEXT, hover),
                new TextAction(TextAction.Type.SUGGEST_COMMAND, entry.suggestion));
    }

    private static void appendStyledUsage(List<RichTextSegment> output, String usage, CommandHelpStyle style) {
        String[] tokens = usage.split(" ");
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) output.add(RichTextSegment.plain(" "));
            int rgb = i == 0 ? style.commandColor() : tokens[i].startsWith("<") || tokens[i].startsWith("[")
                    ? style.argumentColor() : style.literalColor();
            MessageColor color = MessageColor.rgb(rgb);
            if (color.nearestLegacy().rgb() == rgb) color = color.nearestLegacy();
            output.add(new RichTextSegment(tokens[i], color, false, null, null));
        }
    }

    private List<RichTextSegment> navigation(String layout, String key, String navigationCommand, int page,
                                             CommandSender sender, Map<String, Object> values) {
        RichText label = layout == null ? messages.resolve(sender, key, values) : template(layout, sender, values);
        List<RichTextSegment> output = new ArrayList<RichTextSegment>();
        CommandDispatcher.appendWithActions(output, label,
                new TextAction(TextAction.Type.HOVER_TEXT, messages.resolve(sender, CommandMessageKeys.HELP_OPEN_PAGE,
                        MessagePlaceholders.of("page", Integer.valueOf(page))).plainText()),
                new TextAction(TextAction.Type.RUN_COMMAND, navigationCommand));
        return output;
    }

    private void collect(CommandNode parent, CommandSender sender, String prefix, Set<String> seen, List<HelpEntry> entries) {
        for (CommandNode child : parent.children) {
            if (!CommandDispatcher.isAccessible(sender, child)) continue;
            String usage = prefix + " " + child.usageToken();
            if (child.handler != null && (child.handlerAccess == null || child.handlerAccess.test(sender)) && seen.add(usage))
                entries.add(new HelpEntry(usage, description(child, sender), suggestion(usage)));
            collect(child, sender, usage, seen, entries);
        }
    }

    private String description(CommandNode node, CommandSender sender) {
        for (CommandNode current = node; current != null; current = current.parent) {
            String description = current.descriptionKey == null ? current.description
                    : messages.resolve(sender, current.descriptionKey, MessagePlaceholders.none()).plainText();
            if (!description.isEmpty()) return description;
        }
        return "暂无说明";
    }

    private static RichText template(String source, CommandSender sender, Map<String, ?> values) {
        MessagePipeline pipeline = new MessagePipeline(key -> Optional.of(source), "", null, (recipient, text) -> {});
        return pipeline.render(MessageRecipient.commandSender(sender), "help-template", values);
    }
    private static RichTextSegment withText(RichTextSegment source, String text) {
        return new RichTextSegment(text, source.color(), source.bold(), source.italic(), source.underlined(), source.strikethrough(),
                source.obfuscated(), source.hover(), source.click());
    }
    private static String suggestion(String usage) {
        for (int i = 0; i < usage.length(); i++) if (usage.charAt(i) == '<' || usage.charAt(i) == '[') return usage.substring(0, i);
        return usage + " ";
    }
    private static final class HelpEntry {
        final String usage, description, suggestion, category, hover;
        HelpEntry(String usage, String description, String suggestion) { this(usage, description, suggestion, "", ""); }
        HelpEntry(String usage, String description, String suggestion, String category, String hover) {
            this.usage = usage; this.description = description; this.suggestion = suggestion; this.category = category; this.hover = hover;
        }
    }
}
