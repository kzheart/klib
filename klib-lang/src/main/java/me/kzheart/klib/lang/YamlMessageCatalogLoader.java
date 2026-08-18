package me.kzheart.klib.lang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** YAML 库将配置节解码为映射后使用的适配入口。 */
public final class YamlMessageCatalogLoader {
    private YamlMessageCatalogLoader() {
    }

    public static MessageCatalog fromDecodedMap(Map<String, ?> decodedSection) {
        Map<String, Object> flattened = new LinkedHashMap<String, Object>();
        flatten("", decodedSection, flattened);
        return new MapMessageCatalog(flattened);
    }

    private static void flatten(
            String prefix,
            Map<String, ?> source,
            Map<String, Object> target
    ) {
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?>) {
                Map<String, ?> mapping = stringKeyedMap(key, (Map<?, ?>) value);
                // 只有 text 值为字符串的配置节才是富文本条目；
                // 仅包含嵌套 text 映射的配置节仍保持为普通配置节。
                if (mapping.get("text") instanceof String) {
                    putUnique(target, key, rich(key, mapping));
                } else {
                    flatten(key, mapping, target);
                }
            } else {
                putUnique(target, key, value);
            }
        }
    }

    private static RichText rich(String key, Map<String, ?> value) {
        Object text = value.get("text");
        if (!(text instanceof String)) {
            throw new IllegalArgumentException(
                    "Rich catalog entry requires string text (a section with a string 'text'"
                            + " child is treated as rich text): " + key);
        }
        MessageColor color = null;
        Object colorValue = value.get("color");
        if (colorValue != null) {
            color = MessageColor.fromTag(String.valueOf(colorValue));
            if (color == null) {
                throw new IllegalArgumentException("Unknown rich catalog color: " + colorValue);
            }
        }
        TextAction hover = value.containsKey("hover")
                ? new TextAction(TextAction.Type.HOVER_TEXT, String.valueOf(value.get("hover")))
                : null;
        TextAction click = click(key, value.get("click"));
        RichTextSegment segment = new RichTextSegment(
                (String) text,
                color,
                bool(value.get("bold")),
                bool(value.get("italic")),
                bool(value.get("underlined")),
                bool(value.get("strikethrough")),
                bool(value.get("obfuscated")),
                hover,
                click);
        List<RichTextSegment> segments = new ArrayList<RichTextSegment>();
        segments.add(segment);
        return new RichText(segments);
    }

    private static TextAction click(String key, Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Rich catalog click must be a mapping: " + key);
        }
        Map<String, ?> click = stringKeyedMap(key + ".click", (Map<?, ?>) raw);
        Object type = click.get("type");
        Object value = click.get("value");
        if (type == null || value == null) {
            throw new IllegalArgumentException("Rich catalog click requires type and value: " + key);
        }
        final TextAction.Type action;
        try {
            action = TextAction.Type.valueOf(
                    String.valueOf(type).trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Unknown rich catalog click type: " + type, failure);
        }
        if (action == TextAction.Type.HOVER_TEXT) {
            throw new IllegalArgumentException("HOVER_TEXT must use the hover field: " + key);
        }
        return new TextAction(action, String.valueOf(value));
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }

    private static Map<String, ?> stringKeyedMap(String path, Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException("Catalog key must be a string: " + path);
            }
            result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static void putUnique(Map<String, Object> target, String key, Object value) {
        if (target.put(key, value) != null) {
            throw new IllegalArgumentException("Duplicate YAML message key: " + key);
        }
    }
}
