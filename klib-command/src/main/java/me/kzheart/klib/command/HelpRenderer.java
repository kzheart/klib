package me.kzheart.klib.command;

import me.kzheart.klib.command.api.CommandSpec;
import me.kzheart.klib.lang.MessageColor;
import me.kzheart.klib.lang.RichText;
import me.kzheart.klib.lang.RichTextSegment;
import me.kzheart.klib.lang.TextAction;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class HelpRenderer {
    private final CommandMessages messages;

    public HelpRenderer(CommandMessages messages) {
        if (messages == null) {
            throw new NullPointerException("messages");
        }
        this.messages = messages;
    }

    public HelpPage render(CommandSpec spec, CommandSender sender, int requestedPage, int pageSize) {
        if (spec == null) {
            throw new NullPointerException("spec");
        }
        if (!(spec instanceof CommandSpecImpl)) {
            throw new IllegalArgumentException(
                    "命令规格必须由 CommandModule / Scope.command / CommandSpecImpl.command 创建");
        }
        if (sender == null) {
            throw new NullPointerException("sender");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be positive");
        }

        CommandSpecImpl typed = (CommandSpecImpl) spec;
        List<HelpEntry> entries = new ArrayList<HelpEntry>();
        collect(
                typed.root(),
                sender,
                "/" + typed.name(),
                "/" + typed.name(),
                new HashSet<String>(),
                entries);
        int totalPages = Math.max(1, (entries.size() + pageSize - 1) / pageSize);
        int page = Math.max(1, Math.min(requestedPage, totalPages));
        int from = Math.min((page - 1) * pageSize, entries.size());
        int to = Math.min(from + pageSize, entries.size());

        List<RichTextSegment> segments = new ArrayList<RichTextSegment>();
        segments.addAll(messages.resolve(
                sender,
                CommandMessageKeys.HELP_HEADER,
                MessagePlaceholders.of(
                        "command", typed.name(),
                        "page", Integer.valueOf(page),
                        "pages", Integer.valueOf(totalPages))).segments());
        for (HelpEntry entry : entries.subList(from, to)) {
            segments.add(RichTextSegment.plain("\n"));
            String hover = entry.description.isEmpty() ? entry.usage : entry.description;
            appendStyledUsage(segments, entry, hover);
            if (!entry.description.isEmpty()) {
                segments.add(new RichTextSegment(
                        " - ", MessageColor.DARK_GRAY, false, null, null));
                segments.add(new RichTextSegment(
                        entry.description, MessageColor.GRAY, false, null, null));
            }
        }
        if (totalPages > 1) {
            segments.add(RichTextSegment.plain("\n"));
            if (page > 1) {
                segments.addAll(navigation(
                        messages.resolve(
                                sender,
                                CommandMessageKeys.HELP_PREVIOUS,
                                MessagePlaceholders.none()),
                        typed.name(),
                        page - 1,
                        sender));
            }
            if (page > 1 && page < totalPages) {
                segments.add(RichTextSegment.plain("  "));
            }
            if (page < totalPages) {
                segments.addAll(navigation(
                        messages.resolve(
                                sender,
                                CommandMessageKeys.HELP_NEXT,
                                MessagePlaceholders.none()),
                        typed.name(),
                        page + 1,
                        sender));
            }
        }
        return new HelpPage(page, totalPages, new RichText(segments));
    }

    /**
     * 为单个节点生成用法反馈：列出该节点下每个可达处理器的一行用法。
     *
     * @param prefix    已输入部分，如 {@code /gather give}
     * @param maxLines  超过该行数时返回 {@code null}，由调用方退回完整帮助
     * @return 用法文本；没有可展示的分支或分支过多时返回 {@code null}
     */
    RichText usage(CommandNode node, CommandSender sender, String prefix, int maxLines) {
        List<HelpEntry> entries = new ArrayList<HelpEntry>();
        collect(node, sender, prefix, prefix, new HashSet<String>(), entries);
        if (entries.isEmpty() || entries.size() > maxLines) {
            return null;
        }
        List<RichTextSegment> segments = new ArrayList<RichTextSegment>();
        for (HelpEntry entry : entries) {
            if (!segments.isEmpty()) {
                segments.add(RichTextSegment.plain("\n"));
            }
            segments.addAll(messages.resolve(
                    sender,
                    CommandMessageKeys.USAGE,
                    MessagePlaceholders.of("usage", entry.usage)).segments());
        }
        return new RichText(segments);
    }

    private static void appendStyledUsage(
            List<RichTextSegment> output,
            HelpEntry entry,
            String hover
    ) {
        String[] tokens = entry.usage.split(" ");
        TextAction hoverAction = new TextAction(TextAction.Type.HOVER_TEXT, hover);
        TextAction clickAction = new TextAction(
                TextAction.Type.SUGGEST_COMMAND, entry.suggestion);
        for (int index = 0; index < tokens.length; index++) {
            if (index > 0) {
                output.add(new RichTextSegment(
                        " ", MessageColor.DARK_GRAY, false, hoverAction, clickAction));
            }
            String token = tokens[index];
            MessageColor color;
            if (index == 0) {
                color = MessageColor.GOLD;
            } else if (token.startsWith("<") || token.startsWith("[")) {
                color = MessageColor.GRAY;
            } else {
                color = MessageColor.YELLOW;
            }
            output.add(new RichTextSegment(
                    token, color, false, hoverAction, clickAction));
        }
    }

    private List<RichTextSegment> navigation(
            RichText text,
            String command,
            int page,
            CommandSender sender
    ) {
        String value = "/" + command + " help " + page;
        List<RichTextSegment> result = new ArrayList<RichTextSegment>();
        CommandDispatcher.appendWithActions(
                result,
                text,
                new TextAction(
                        TextAction.Type.HOVER_TEXT,
                        messages.resolve(
                                sender,
                                CommandMessageKeys.HELP_OPEN_PAGE,
                                MessagePlaceholders.of("page", Integer.valueOf(page))).plainText()),
                new TextAction(TextAction.Type.RUN_COMMAND, value));
        return result;
    }

    private void collect(
            CommandNode parent,
            CommandSender sender,
            String parentUsage,
            String literalPath,
            Set<String> seenLiteralPaths,
            List<HelpEntry> entries
    ) {
        for (CommandNode child : parent.children) {
            if (!CommandDispatcher.isAccessible(sender, child)) {
                continue;
            }
            String usage = parentUsage + " " + child.usageToken();
            // argument 节点不延伸 literal 路径，同一 literal 路径只保留最短的一条帮助条目。
            String path = child.literal == null ? literalPath : literalPath + " " + child.literal;
            if (child.handler != null && seenLiteralPaths.add(path)) {
                String description = child.descriptionKey == null
                        ? child.description
                        : messages.resolve(
                                sender,
                                child.descriptionKey,
                                MessagePlaceholders.none()).plainText();
                entries.add(new HelpEntry(usage, description, suggestion(usage)));
            }
            collect(child, sender, usage, path, seenLiteralPaths, entries);
        }
    }

    private static String suggestion(String usage) {
        // 同时按 `<` 与 `[` 截断，避免点击把 `[page]` 一类占位符塞进输入框。
        for (int index = 0; index < usage.length(); index++) {
            char current = usage.charAt(index);
            if (current == '<' || current == '[') {
                return usage.substring(0, index);
            }
        }
        return usage + " ";
    }

    private static final class HelpEntry {
        private final String usage;
        private final String description;
        private final String suggestion;

        private HelpEntry(String usage, String description, String suggestion) {
            this.usage = usage;
            this.description = description;
            this.suggestion = suggestion;
        }
    }
}
