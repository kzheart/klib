package me.kzheart.klib.lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/** 固定执行消息键、占位符、前缀、格式化、可选 PAPI 和路由阶段的管线。 */
public final class MessagePipeline {
    private static final Logger LOGGER = Logger.getLogger(MessagePipeline.class.getName());

    private final Set<String> warnedMissingKeys =
            ConcurrentHashMap.newKeySet();
    private final MessageCatalog catalog;
    private volatile String prefix;
    private final PlaceholderApi placeholderApi;
    private final MessageRouter router;

    public MessagePipeline(MessageCatalog catalog, String prefix, PlaceholderApi placeholderApi, MessageRouter router) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.placeholderApi = placeholderApi;
        this.router = Objects.requireNonNull(router, "router");
    }

    public RichText send(MessageRecipient recipient, String key, Map<String, ?> placeholders) {
        return sendInternal(recipient, null, key, placeholders);
    }

    public RichText send(MessageRecipient recipient, MessageRoute route, String key, Map<String, ?> placeholders) {
        return sendInternal(recipient, Objects.requireNonNull(route, "route"), key, placeholders);
    }

    private RichText sendInternal(
            MessageRecipient recipient,
            MessageRoute routeOverride,
            String key,
            Map<String, ?> placeholders
    ) {
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(placeholders, "placeholders");
        ResolvedMessage resolved = resolveMessage(recipient, key, placeholders);
        router.route(
                recipient,
                routeOverride == null ? resolved.route : routeOverride,
                resolved.message);
        return resolved.message;
    }

    public RichText send(MessageRecipient recipient, String key) {
        return sendInternal(recipient, null, key, Collections.<String, Object>emptyMap());
    }

    public RichText send(MessageRecipient recipient, MessageRoute route, String key) {
        return send(recipient, route, key, Collections.<String, Object>emptyMap());
    }

    /** 解析消息但不进行路由，适用于自行负责投递的适配器。 */
    public RichText render(MessageRecipient recipient, String key, Map<String, ?> placeholders) {
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(placeholders, "placeholders");
        return resolveMessage(recipient, key, placeholders).message;
    }

    public RichText render(MessageRecipient recipient, String key) {
        return render(recipient, key, Collections.<String, Object>emptyMap());
    }

    private ResolvedMessage resolveMessage(
            MessageRecipient recipient,
            String key,
            Map<String, ?> placeholders
    ) {
        ResolvedMessage template = resolveTemplate(key);
        // PAPI 只处理消息目录拥有的模板文本。命名值会在之后插入，
        // 因此无法在本次求值中夹带第二个 PAPI 表达式。
        RichText expandedTemplate = placeholderApi == null
                ? template.message
                : applyPlaceholderApi(recipient, template.message);
        return new ResolvedMessage(
                template.route,
                applyNamedPlaceholders(expandedTemplate, placeholders));
    }

    /**
     * 先解析模板（路由、{prefix}、MiniMessage），再将占位符值作为字面文本替换到已解析片段中。
     * 值不会进入路由提取、前缀展开或 MiniMessage 解析阶段，因此玩家控制的输入无法注入格式或点击操作。
     */
    @SuppressWarnings("unchecked")
    private ResolvedMessage resolveTemplate(String key) {
        Object entry = catalog.findAny(key).orElse(null);
        final ResolvedMessage template;
        if (entry instanceof RichText) {
            ResolvedMessage routed = extractRoute((RichText) entry);
            template = new ResolvedMessage(routed.route, replacePrefix(routed.message));
        } else if (entry != null) {
            String source = entry instanceof List<?>
                    ? joinLines((List<String>) entry)
                    : String.valueOf(entry);
            ResolvedText routed = extractRoute(source);
            template = new ResolvedMessage(routed.route, replacePrefix(parse(routed.text)));
        } else {
            return new ResolvedMessage(MessageRoute.CHAT, missingKeyText(key));
        }
        return template;
    }

    private RichText missingKeyText(String key) {
        if (warnedMissingKeys.add(key)) {
            LOGGER.warning("Missing language key: " + key);
        }
        return new RichText(Collections.singletonList(new RichTextSegment(
                "[missing:" + key + "]", MessageColor.RED, false, null, null)));
    }

    private static RichText applyNamedPlaceholders(RichText source, Map<String, ?> placeholders) {
        if (placeholders.isEmpty()) {
            return source;
        }
        List<RichTextSegment> result = new ArrayList<RichTextSegment>();
        for (RichTextSegment segment : source.segments()) {
            result.add(segment.withText(applyPlaceholders(segment.text(), placeholders)));
        }
        return new RichText(result);
    }

    /** 单遍替换：替换值按字面量输出，不会再次扫描。 */
    private static String applyPlaceholders(String source, Map<String, ?> placeholders) {
        if (source.indexOf('{') < 0) {
            return source;
        }
        StringBuilder result = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '{') {
                int close = source.indexOf('}', index + 1);
                if (close > index) {
                    String name = source.substring(index + 1, close);
                    if (!"prefix".equals(name) && placeholders.containsKey(name)) {
                        result.append(String.valueOf(placeholders.get(name)));
                        index = close + 1;
                        continue;
                    }
                }
            }
            result.append(current);
            index++;
        }
        return result.toString();
    }

    private RichText applyPlaceholderApi(MessageRecipient recipient, RichText source) {
        List<RichTextSegment> expanded = new ArrayList<RichTextSegment>();
        for (RichTextSegment segment : source.segments()) {
            expanded.add(segment.withText(placeholderApi.expand(recipient, segment.text())));
        }
        return new RichText(expanded);
    }

    private RichText replacePrefix(RichText source) {
        RichText parsedPrefix = parse(prefix);
        List<RichTextSegment> result = new ArrayList<RichTextSegment>();
        for (RichTextSegment segment : source.segments()) {
            String text = segment.text();
            int start = 0;
            int match;
            while ((match = text.indexOf("{prefix}", start)) >= 0) {
                if (match > start) {
                    result.add(segment.withText(text.substring(start, match)));
                }
                result.addAll(parsedPrefix.segments());
                start = match + "{prefix}".length();
            }
            if (start < text.length()) {
                result.add(segment.withText(text.substring(start)));
            }
        }
        return new RichText(result);
    }

    void updatePrefix(String replacement) {
        prefix = Objects.requireNonNull(replacement, "replacement");
    }

    private static ResolvedText extractRoute(String source) {
        String lower = source.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("actionbar:")) {
            return new ResolvedText(MessageRoute.ACTION_BAR, stripRoutePrefix(source, 10));
        }
        if (lower.startsWith("title:")) {
            return new ResolvedText(MessageRoute.TITLE, stripRoutePrefix(source, 6));
        }
        if (lower.startsWith("bossbar:")) {
            return new ResolvedText(MessageRoute.BOSS_BAR, stripRoutePrefix(source, 8));
        }
        if (lower.startsWith("chat:")) {
            return new ResolvedText(MessageRoute.CHAT, stripRoutePrefix(source, 5));
        }
        return new ResolvedText(MessageRoute.CHAT, source);
    }

    private static ResolvedMessage extractRoute(RichText source) {
        ResolvedText routed = extractRoute(source.plainText());
        int removed = source.plainText().length() - routed.text.length();
        if (removed == 0) {
            return new ResolvedMessage(routed.route, source);
        }
        List<RichTextSegment> stripped = new ArrayList<RichTextSegment>();
        int remaining = removed;
        for (RichTextSegment segment : source.segments()) {
            if (remaining >= segment.text().length()) {
                remaining -= segment.text().length();
            } else {
                stripped.add(segment.withText(segment.text().substring(remaining)));
                remaining = 0;
            }
        }
        return new ResolvedMessage(routed.route, new RichText(stripped));
    }

    private static String stripRoutePrefix(String source, int length) {
        int contentStart = length;
        if (contentStart < source.length() && source.charAt(contentStart) == ' ') {
            contentStart++;
        }
        return source.substring(contentStart);
    }

    private static String joinLines(List<String> lines) {
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(line);
        }
        return result.toString();
    }

    private static RichText parse(String source) {
        return AdventureMessageParser.parse(source);
    }

    private static final class ResolvedText {
        private final MessageRoute route;
        private final String text;

        private ResolvedText(MessageRoute route, String text) {
            this.route = route;
            this.text = text;
        }
    }

    private static final class ResolvedMessage {
        private final MessageRoute route;
        private final RichText message;

        private ResolvedMessage(MessageRoute route, RichText message) {
            this.route = route;
            this.message = message;
        }
    }
}
