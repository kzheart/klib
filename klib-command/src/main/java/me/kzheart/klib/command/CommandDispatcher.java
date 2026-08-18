package me.kzheart.klib.command;

import me.kzheart.klib.KLogger;
import me.kzheart.klib.command.api.CommandArgument;
import me.kzheart.klib.command.api.CommandSpec;
import me.kzheart.klib.lang.MessageColor;
import me.kzheart.klib.lang.RichText;
import me.kzheart.klib.lang.RichTextSegment;
import me.kzheart.klib.lang.TextAction;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicLong;
import me.kzheart.klib.diagnostic.DiagnosticSource;

public final class CommandDispatcher implements DiagnosticSource {
    private static final int DEFAULT_HELP_PAGE_SIZE = 8;
    private static final Logger FALLBACK_LOGGER =
            Logger.getLogger(CommandDispatcher.class.getName());

    private final CommandSpecImpl spec;
    private final PlayerResolver players;
    private final RichTextSink output;
    private final CommandMessages messages;
    private final HelpRenderer helpRenderer;
    private final KLogger logger;
    private final AtomicLong invocations = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private volatile String lastFailureType = "";

    public CommandDispatcher(CommandSpec spec) {
        this(
                spec,
                BukkitPlayerResolver.INSTANCE,
                SpigotRichTextSink.INSTANCE,
                DefaultCommandMessages.INSTANCE,
                null);
    }

    public CommandDispatcher(CommandSpec spec, PlayerResolver players, RichTextSink output) {
        this(spec, players, output, DefaultCommandMessages.INSTANCE, null);
    }

    public CommandDispatcher(
            CommandSpec spec,
            PlayerResolver players,
            RichTextSink output,
            CommandMessages messages
    ) {
        this(spec, players, output, messages, null);
    }

    public CommandDispatcher(
            CommandSpec spec,
            PlayerResolver players,
            RichTextSink output,
            CommandMessages messages,
            KLogger logger
    ) {
        if (spec == null) {
            throw new NullPointerException("spec");
        }
        if (!(spec instanceof CommandSpecImpl)) {
            throw new IllegalArgumentException(
                    "命令规格必须由 CommandModule / Scope.command / CommandSpecImpl.command 创建");
        }
        if (players == null) {
            throw new NullPointerException("players");
        }
        if (output == null) {
            throw new NullPointerException("output");
        }
        if (messages == null) {
            throw new NullPointerException("messages");
        }
        this.spec = (CommandSpecImpl) spec;
        this.players = players;
        this.output = output;
        this.messages = messages;
        this.helpRenderer = new HelpRenderer(messages);
        this.logger = logger;
    }

    public CommandResult execute(CommandSender sender, String[] rawArgs) {
        requireInvocation(sender, rawArgs);
        invocations.incrementAndGet();
        String[] args = stripEmptyTokens(rawArgs, false);
        CommandNode current = spec.root();
        CommandResult restriction = restriction(sender, current);
        if (restriction != null) {
            return emit(sender, restriction);
        }

        // LinkedHashMap 保留解析顺序供按名读取使用；Arg 不覆写 equals/hashCode，键仍按对象身份匹配。
        Map<CommandArgument<?>, Object> values = new LinkedHashMap<CommandArgument<?>, Object>();
        List<String> consumed = new ArrayList<String>();
        int index = 0;
        while (index < args.length) {
            String token = args[index];
            CommandNode literal = findLiteral(current, token);
            CommandResult literalRestriction = literal == null
                    ? null
                    : restriction(sender, literal);
            if (literal != null && literalRestriction == null) {
                current = literal;
                consumed.add(literal.literal);
                index++;
                continue;
            }

            // 无权限的 literal 不遮蔽同级 argument：先尝试参数解析，两者都失败再报限制。
            ParsedArgument parsed = parseArgument(current, sender, args, index);
            if (parsed.node != null) {
                restriction = restriction(sender, parsed.node);
                if (restriction == null) {
                    values.put(parsed.node.argument, parsed.value);
                    current = parsed.node;
                    for (int token2 = index; token2 < parsed.nextIndex; token2++) {
                        consumed.add(args[token2]);
                    }
                    index = parsed.nextIndex;
                    continue;
                }
                return emit(sender, literal == null ? restriction : literalRestriction);
            }
            if (literal != null) {
                return emit(sender, literalRestriction);
            }

            List<String> suggestions = suggestions(current, sender, token);
            RichText detail = parsed.error == null
                    ? messages.resolve(
                            sender,
                            CommandMessageKeys.UNKNOWN_ARGUMENT,
                            MessagePlaceholders.of("argument", token))
                    : messages.resolve(
                            sender,
                            parsed.error.key(),
                            parsed.error.placeholders());
            CommandResult.Status status = parsed.error == null
                    ? CommandResult.Status.UNKNOWN_ARGUMENT
                    : CommandResult.Status.INVALID_ARGUMENT;
            return emit(sender, CommandResult.message(
                    status,
                    errorAt(sender, args, index, detail, suggestions)));
        }

        CommandNode optional = optionalChild(current, sender);
        while (current.handler == null && optional != null) {
            values.put(optional.argument, optional.argument.defaultValue());
            current = optional;
            optional = optionalChild(current, sender);
        }

        if (current.handler != null) {
            CommandContextImpl context = new CommandContextImpl(sender, spec.name(), values);
            try {
                if (current.handler instanceof DispatcherAwareCommandHandler) {
                    ((DispatcherAwareCommandHandler) current.handler).execute(context, this);
                } else {
                    current.handler.execute(context);
                }
            } catch (CommandFailure failure) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                reportFailure(cause);
                return emit(sender, CommandResult.message(
                        CommandResult.Status.FAILED,
                        failureText(sender, cause, failure.messageKey())));
            } catch (RuntimeException failure) {
                reportFailure(failure);
                return emit(sender, CommandResult.message(
                        CommandResult.Status.FAILED,
                        message(sender, CommandMessageKeys.INTERNAL_ERROR)));
            } catch (Error failure) {
                reportFailure(failure);
                emit(sender, CommandResult.message(
                        CommandResult.Status.FAILED,
                        message(sender, CommandMessageKeys.INTERNAL_ERROR)));
                throw failure;
            }
            return CommandResult.success();
        }
        if (!current.children.isEmpty()) {
            // 已经进入子命令却缺少后续参数时，只反馈该节点的用法，不刷整个命令的帮助第一页。
            if (current != spec.root()) {
                RichText usage = helpRenderer.usage(
                        current,
                        sender,
                        usagePrefix(consumed),
                        DEFAULT_HELP_PAGE_SIZE);
                if (usage != null) {
                    return emit(sender, CommandResult.message(
                            CommandResult.Status.INCOMPLETE, usage));
                }
            }
            HelpPage help = helpRenderer.render(spec, sender, 1, DEFAULT_HELP_PAGE_SIZE);
            return emit(sender, CommandResult.message(CommandResult.Status.HELP, help.content()));
        }
        return emit(sender, CommandResult.message(
                CommandResult.Status.INCOMPLETE,
                message(sender, CommandMessageKeys.INCOMPLETE)));
    }

    private void reportFailure(Throwable failure) {
        failures.incrementAndGet();
        lastFailureType = failure.getClass().getName();
        if (logger != null) {
            logger.error("命令处理失败: /" + spec.name(), failure);
        } else {
            FALLBACK_LOGGER.log(Level.SEVERE, "命令处理失败: /" + spec.name(), failure);
        }
    }

    @Override
    public String diagnosticName() {
        return "command";
    }

    @Override
    public Map<String, ?> diagnosticSnapshot() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("name", spec.name());
        result.put("invocations", invocations.get());
        result.put("failures", failures.get());
        result.put("last_failure", lastFailureType);
        return result;
    }

    void sendFailure(CommandSender sender, Throwable failure) {
        sendFailure(sender, failure, null);
    }

    /**
     * 失败反馈。{@code reasonKey} 非空且异常携带可回显原因时使用该键并注入 {@code reason}，
     * 否则退回通用内部错误消息。
     */
    void sendFailure(CommandSender sender, Throwable failure, String reasonKey) {
        reportFailure(failure);
        emit(sender, CommandResult.message(
                CommandResult.Status.FAILED,
                failureText(sender, failure, reasonKey)));
    }

    private RichText failureText(CommandSender sender, Throwable failure, String reasonKey) {
        String reason = reasonKey == null ? null : displayableReason(failure);
        return reason == null
                ? message(sender, CommandMessageKeys.INTERNAL_ERROR)
                : messages.resolve(sender, reasonKey, MessagePlaceholders.of("reason", reason));
    }

    private static final int MAX_REASON_LENGTH = 200;

    /**
     * 只回显 klib 配置异常族携带的原因（其 message 由 klib 自己构造，形如 {@code 文件:路径: 原因}）。
     * klib-command 不依赖 klib-config，因此按类型全名判定。
     */
    private static String displayableReason(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (isConfigException(current.getClass())) {
                return sanitizeReason(current.getMessage());
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return null;
    }

    private static boolean isConfigException(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if ("me.kzheart.klib.config.ConfigException".equals(current.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 原因是内部构造的文本而非玩家输入，但仍去掉 legacy 颜色码并限长，
     * 避免它在 {@link DefaultCommandMessages} 一类先插值再解析的实现里改变后续着色。
     * MiniMessage 管线由 {@code MessagePipeline} 在解析后插值，天然不解析值内标签。
     */
    private static String sanitizeReason(String message) {
        if (message == null) {
            return null;
        }
        StringBuilder cleaned = new StringBuilder(message.length());
        for (int index = 0; index < message.length(); index++) {
            char current = message.charAt(index);
            if (current == '§') {
                index++; // 连同颜色码字符一起丢弃
                continue;
            }
            cleaned.append(current == '\n' || current == '\r' ? ' ' : current);
        }
        String result = cleaned.toString().trim();
        if (result.isEmpty()) {
            return null;
        }
        return result.length() <= MAX_REASON_LENGTH
                ? result
                : result.substring(0, MAX_REASON_LENGTH - 1) + "…";
    }

    private String usagePrefix(List<String> consumed) {
        StringBuilder prefix = new StringBuilder("/").append(spec.name());
        for (String token : consumed) {
            prefix.append(' ').append(token);
        }
        return prefix.toString();
    }

    public List<String> complete(CommandSender sender, String[] rawArgs) {
        requireInvocation(sender, rawArgs);
        String[] args = stripEmptyTokens(rawArgs, true);
        CommandNode current = spec.root();
        if (!isAccessible(sender, current)) {
            return Collections.emptyList();
        }
        if (args.length == 0) {
            return suggestions(current, sender, "");
        }

        int index = 0;
        while (index < args.length - 1) {
            String token = args[index];
            CommandNode literal = findLiteral(current, token);
            if (literal != null && isAccessible(sender, literal)) {
                current = literal;
                index++;
                continue;
            }
            // 与 execute 一致：无权限的 literal 不遮蔽同级 argument。
            ParsedArgument parsed = parseArgument(current, sender, args, index);
            if (parsed.node == null || !isAccessible(sender, parsed.node)) {
                return Collections.emptyList();
            }
            current = parsed.node;
            index = parsed.nextIndex;
            if (index >= args.length) {
                return Collections.emptyList();
            }
        }
        return suggestions(current, sender, args[args.length - 1]);
    }

    public HelpPage renderHelp(CommandSender sender, int page, int pageSize) {
        return helpRenderer.render(spec, sender, page, pageSize);
    }

    public HelpPage sendHelp(CommandSender sender, int page, int pageSize) {
        HelpPage help = renderHelp(sender, page, pageSize);
        output.send(sender, help.content());
        return help;
    }

    static boolean isAccessible(CommandSender sender, CommandNode node) {
        return (node.permission == null || sender.hasPermission(node.permission))
                && (!node.playerOnly || sender instanceof Player);
    }

    private ParsedArgument parseArgument(
            CommandNode current,
            CommandSender sender,
            String[] args,
            int index
    ) {
        ArgumentException firstError = null;
        for (CommandNode child : current.children) {
            if (child.argument == null || !isAccessible(sender, child)) {
                continue;
            }
            String input = child.argument.isGreedy() ? join(args, index) : args[index];
            try {
                Object value = child.argument.parse(input, players);
                return new ParsedArgument(
                        child,
                        value,
                        child.argument.isGreedy() ? args.length : index + 1,
                        null);
            } catch (ArgumentException exception) {
                if (firstError == null) {
                    firstError = exception;
                }
            }
        }
        return new ParsedArgument(null, null, index, firstError);
    }

    private List<String> suggestions(CommandNode current, CommandSender sender, String prefix) {
        Set<String> suggestions = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        String normalized = prefix.toLowerCase(Locale.ROOT);
        for (CommandNode child : current.children) {
            if (!isAccessible(sender, child)) {
                continue;
            }
            if (child.literal != null && child.literal.startsWith(normalized)) {
                suggestions.add(child.literal);
            } else if (child.argument != null) {
                suggestions.addAll(child.argument.suggest(sender, prefix, players));
            }
        }
        if (suggestions.isEmpty()) {
            String nearest = nearestLiteral(current, sender, prefix);
            if (nearest != null) {
                suggestions.add(nearest);
            }
        }
        return new ArrayList<String>(suggestions);
    }

    private CommandResult restriction(CommandSender sender, CommandNode node) {
        if (node.permission != null && !sender.hasPermission(node.permission)) {
            return CommandResult.message(
                    CommandResult.Status.NO_PERMISSION,
                    message(sender, CommandMessageKeys.NO_PERMISSION));
        }
        if (node.playerOnly && !(sender instanceof Player)) {
            return CommandResult.message(
                    CommandResult.Status.PLAYER_ONLY,
                    message(sender, CommandMessageKeys.PLAYER_ONLY));
        }
        return null;
    }

    private CommandResult emit(CommandSender sender, CommandResult result) {
        if (result.message() != null) {
            output.send(sender, result.message());
        }
        return result;
    }

    private RichText errorAt(
            CommandSender sender,
            String[] args,
            int index,
            RichText detail,
            List<String> suggestions
    ) {
        // 比例字体下 caret 无法对齐，改为行内高亮：复读命令 + 错误 token 红色下划线 + 行尾原因。
        StringBuilder prefix = new StringBuilder("/").append(spec.name());
        for (int current = 0; current < index; current++) {
            prefix.append(' ').append(args[current]);
        }
        prefix.append(' ');

        List<RichTextSegment> segments = new ArrayList<RichTextSegment>();
        segments.add(new RichTextSegment(
                prefix.toString(), MessageColor.GRAY, false, null, null));
        segments.add(new RichTextSegment(
                args[index], MessageColor.RED, false, false, true, false, false, null, null));
        if (index + 1 < args.length) {
            segments.add(new RichTextSegment(
                    " " + join(args, index + 1), MessageColor.GRAY, false, null, null));
        }
        segments.add(new RichTextSegment(" ← ", MessageColor.RED, false, null, null));
        segments.addAll(detail.segments());
        if (!suggestions.isEmpty()) {
            String suggestion = suggestions.get(0);
            String command = replacement(args, index, suggestion);
            segments.add(RichTextSegment.plain("\n"));
            RichText suggestionText = messages.resolve(
                    sender,
                    CommandMessageKeys.SUGGESTION,
                    MessagePlaceholders.of("suggestion", suggestion));
            appendWithActions(
                    segments,
                    suggestionText,
                    new TextAction(TextAction.Type.HOVER_TEXT, command),
                    new TextAction(TextAction.Type.SUGGEST_COMMAND, command));
        }
        return new RichText(segments);
    }

    void sendMessage(CommandSender sender, String key) {
        output.send(sender, message(sender, key));
    }

    int defaultHelpPageSize() {
        return DEFAULT_HELP_PAGE_SIZE;
    }

    private RichText message(CommandSender sender, String key) {
        return messages.resolve(sender, key, MessagePlaceholders.none());
    }

    static void appendWithActions(
            List<RichTextSegment> output,
            RichText text,
            TextAction hover,
            TextAction click
    ) {
        for (RichTextSegment segment : text.segments()) {
            output.add(new RichTextSegment(
                    segment.text(),
                    segment.color(),
                    segment.bold(),
                    segment.italic(),
                    segment.underlined(),
                    segment.strikethrough(),
                    segment.obfuscated(),
                    hover == null ? segment.hover() : hover,
                    click == null ? segment.click() : click));
        }
    }

    private CommandNode optionalChild(CommandNode current, CommandSender sender) {
        for (CommandNode child : current.children) {
            if (child.argument != null
                    && child.argument.isOptional()
                    && isAccessible(sender, child)) {
                return child;
            }
        }
        return null;
    }

    private String replacement(String[] args, int index, String suggestion) {
        List<String> replaced = new ArrayList<String>();
        Collections.addAll(replaced, args);
        replaced.set(index, suggestion);
        return "/" + spec.name() + " " + String.join(" ", replaced);
    }

    private static CommandNode findLiteral(CommandNode current, String token) {
        for (CommandNode child : current.children) {
            if (child.literal != null && child.literal.equalsIgnoreCase(token)) {
                return child;
            }
        }
        return null;
    }

    private static String nearestLiteral(CommandNode current, CommandSender sender, String input) {
        String nearest = null;
        int distance = Integer.MAX_VALUE;
        for (CommandNode child : current.children) {
            if (child.literal == null || !isAccessible(sender, child)) {
                continue;
            }
            int candidate = levenshtein(input.toLowerCase(Locale.ROOT), child.literal);
            if (candidate < distance) {
                distance = candidate;
                nearest = child.literal;
            }
        }
        int threshold = Math.max(2, input.length() / 3);
        return distance <= threshold ? nearest : null;
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int index = 0; index <= right.length(); index++) {
            previous[index] = index;
        }
        for (int leftIndex = 1; leftIndex <= left.length(); leftIndex++) {
            current[0] = leftIndex;
            for (int rightIndex = 1; rightIndex <= right.length(); rightIndex++) {
                int substitution = left.charAt(leftIndex - 1) == right.charAt(rightIndex - 1) ? 0 : 1;
                current[rightIndex] = Math.min(
                        Math.min(current[rightIndex - 1] + 1, previous[rightIndex] + 1),
                        previous[rightIndex - 1] + substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private static String join(String[] args, int from) {
        StringBuilder joined = new StringBuilder();
        for (int index = from; index < args.length; index++) {
            if (index > from) {
                joined.append(' ');
            }
            joined.append(args[index]);
        }
        return joined.toString();
    }

    private static String[] stripEmptyTokens(String[] args, boolean keepTrailing) {
        List<String> filtered = new ArrayList<String>(args.length);
        for (int index = 0; index < args.length; index++) {
            if (!args[index].isEmpty() || (keepTrailing && index == args.length - 1)) {
                filtered.add(args[index]);
            }
        }
        return filtered.size() == args.length
                ? args
                : filtered.toArray(new String[filtered.size()]);
    }

    private static void requireInvocation(CommandSender sender, String[] args) {
        if (sender == null) {
            throw new NullPointerException("sender");
        }
        if (args == null) {
            throw new NullPointerException("args");
        }
        for (String argument : args) {
            if (argument == null) {
                throw new IllegalArgumentException("args must not contain null");
            }
        }
    }

    private static final class ParsedArgument {
        private final CommandNode node;
        private final Object value;
        private final int nextIndex;
        private final ArgumentException error;

        private ParsedArgument(
                CommandNode node,
                Object value,
                int nextIndex,
                ArgumentException error
        ) {
            this.node = node;
            this.value = value;
            this.nextIndex = nextIndex;
            this.error = error;
        }
    }
}
