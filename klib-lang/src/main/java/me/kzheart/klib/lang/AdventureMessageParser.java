package me.kzheart.klib.lang;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.KeybindComponent;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.ArrayList;
import java.util.List;

final class AdventureMessageParser {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private AdventureMessageParser() {
    }

    static RichText parse(String source) {
        Component component = MINI_MESSAGE.deserialize(convertLegacyCodes(source));
        List<RichTextSegment> segments = new ArrayList<RichTextSegment>();
        append(component, new EffectiveStyle(), segments);
        return new RichText(segments);
    }

    private static void append(Component component, EffectiveStyle inherited, List<RichTextSegment> output) {
        EffectiveStyle style = inherited.merge(component.style());
        String content = content(component);
        if (!content.isEmpty()) {
            output.add(new RichTextSegment(content, style.color, style.bold, style.italic, style.underlined,
                    style.strikethrough, style.obfuscated, style.hover, style.click));
        }
        if (component instanceof TranslatableComponent) {
            for (TranslationArgument argument : ((TranslatableComponent) component).arguments()) {
                Object value = argument.value();
                if (value instanceof Component) {
                    append((Component) value, style, output);
                } else {
                    output.add(new RichTextSegment(String.valueOf(value), style.color, style.bold, style.italic,
                            style.underlined, style.strikethrough, style.obfuscated, style.hover, style.click));
                }
            }
        }
        for (Component child : component.children()) {
            append(child, style, output);
        }
    }

    private static String content(Component component) {
        if (component instanceof TextComponent) {
            return ((TextComponent) component).content();
        }
        if (component instanceof TranslatableComponent) {
            TranslatableComponent translatable = (TranslatableComponent) component;
            return translatable.fallback() == null ? translatable.key() : translatable.fallback();
        }
        if (component instanceof KeybindComponent) {
            return ((KeybindComponent) component).keybind();
        }
        return "";
    }

    private static String convertLegacyCodes(String source) {
        StringBuilder converted = new StringBuilder(source.length() + 32);
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '<' && !isEscaped(source, index)) {
                int tagEnd = findTagEnd(source, index + 1);
                if (tagEnd >= 0) {
                    converted.append(source, index, tagEnd + 1);
                    index = tagEnd + 1;
                    continue;
                }
            }
            if (current == '&' && index + 1 < source.length()) {
                if (source.charAt(index + 1) == '#' && index + 8 <= source.length()) {
                    String hex = source.substring(index + 2, index + 8);
                    if (hex.matches("[0-9a-fA-F]{6}")) {
                        converted.append("<reset><#").append(hex).append('>');
                        index += 8;
                        continue;
                    }
                }
                String replacement = legacyTag(source.charAt(index + 1));
                if (replacement != null) {
                    converted.append(replacement);
                    index += 2;
                    continue;
                }
            }
            converted.append(current);
            index++;
        }
        return converted.toString();
    }

    /** 仅当前面有奇数个反斜杠时，字符才视为被转义。 */
    private static boolean isEscaped(String source, int index) {
        int backslashes = 0;
        for (int cursor = index - 1; cursor >= 0 && source.charAt(cursor) == '\\'; cursor--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    private static int findTagEnd(String source, int start) {
        boolean escaped = false;
        for (int index = start; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '>' && !escaped) {
                return index;
            }
            escaped = current == '\\' && !escaped;
            if (current != '\\') {
                escaped = false;
            }
        }
        return -1;
    }

    private static String legacyTag(char rawCode) {
        char code = Character.toLowerCase(rawCode);
        MessageColor color = MessageColor.fromLegacy(code);
        if (color != null) {
            return "<reset><" + color.toString() + ">";
        }
        switch (code) {
            case 'k': return "<obfuscated>";
            case 'l': return "<bold>";
            case 'm': return "<strikethrough>";
            case 'n': return "<underlined>";
            case 'o': return "<italic>";
            case 'r': return "<reset>";
            default: return null;
        }
    }

    private static final class EffectiveStyle {
        private MessageColor color;
        private boolean bold;
        private boolean italic;
        private boolean underlined;
        private boolean strikethrough;
        private boolean obfuscated;
        private TextAction hover;
        private TextAction click;

        private EffectiveStyle merge(Style local) {
            EffectiveStyle merged = copy();
            TextColor localColor = local.color();
            if (localColor != null) {
                merged.color = MessageColor.rgb(localColor.value());
            }
            merged.bold = decoration(local, TextDecoration.BOLD, bold);
            merged.italic = decoration(local, TextDecoration.ITALIC, italic);
            merged.underlined = decoration(local, TextDecoration.UNDERLINED, underlined);
            merged.strikethrough = decoration(local, TextDecoration.STRIKETHROUGH, strikethrough);
            merged.obfuscated = decoration(local, TextDecoration.OBFUSCATED, obfuscated);
            if (local.hoverEvent() != null) {
                merged.hover = hover(local.hoverEvent());
            }
            if (local.clickEvent() != null) {
                merged.click = click(local.clickEvent());
            }
            return merged;
        }

        private EffectiveStyle copy() {
            EffectiveStyle copy = new EffectiveStyle();
            copy.color = color;
            copy.bold = bold;
            copy.italic = italic;
            copy.underlined = underlined;
            copy.strikethrough = strikethrough;
            copy.obfuscated = obfuscated;
            copy.hover = hover;
            copy.click = click;
            return copy;
        }

        private static boolean decoration(Style style, TextDecoration decoration, boolean inherited) {
            TextDecoration.State state = style.decoration(decoration);
            return state == TextDecoration.State.NOT_SET ? inherited : state == TextDecoration.State.TRUE;
        }

        private static TextAction hover(HoverEvent<?> event) {
            if (event.action() == HoverEvent.Action.SHOW_TEXT) {
                RichText value = parseComponent((Component) event.value());
                return new TextAction(TextAction.Type.HOVER_TEXT, value.plainText());
            }
            if (event.action() == HoverEvent.Action.SHOW_ITEM) {
                return itemHover(event.value());
            }
            if (event.action() == HoverEvent.Action.SHOW_ENTITY) {
                return entityHover(event.value());
            }
            return null;
        }

        /** 构建可读的物品描述；字段不可用时移除悬停内容。 */
        private static TextAction itemHover(Object value) {
            try {
                HoverEvent.ShowItem item = (HoverEvent.ShowItem) value;
                StringBuilder text = new StringBuilder(item.item().asString());
                if (item.count() > 1) {
                    text.append(" x").append(item.count());
                }
                return new TextAction(TextAction.Type.HOVER_ITEM, text.toString());
            } catch (RuntimeException unavailable) {
                return null;
            } catch (LinkageError unavailable) {
                return null;
            }
        }

        /** 构建可读的实体描述；字段不可用时移除悬停内容。 */
        private static TextAction entityHover(Object value) {
            try {
                HoverEvent.ShowEntity entity = (HoverEvent.ShowEntity) value;
                StringBuilder text = new StringBuilder(entity.type().asString());
                Component name = entity.name();
                if (name != null) {
                    String plain = parseComponent(name).plainText();
                    if (!plain.isEmpty()) {
                        text.append(" (").append(plain).append(')');
                    }
                }
                return new TextAction(TextAction.Type.HOVER_ENTITY, text.toString());
            } catch (RuntimeException unavailable) {
                return null;
            } catch (LinkageError unavailable) {
                return null;
            }
        }

        private static TextAction click(ClickEvent event) {
            TextAction.Type type;
            if (event.action() == ClickEvent.Action.RUN_COMMAND) {
                type = TextAction.Type.RUN_COMMAND;
            } else if (event.action() == ClickEvent.Action.SUGGEST_COMMAND) {
                type = TextAction.Type.SUGGEST_COMMAND;
            } else if (event.action() == ClickEvent.Action.OPEN_URL) {
                type = TextAction.Type.OPEN_URL;
            } else if (event.action() == ClickEvent.Action.OPEN_FILE) {
                type = TextAction.Type.OPEN_FILE;
            } else if (event.action() == ClickEvent.Action.CHANGE_PAGE) {
                type = TextAction.Type.CHANGE_PAGE;
            } else {
                type = TextAction.Type.COPY_TO_CLIPBOARD;
            }
            return new TextAction(type, event.value());
        }

        private static RichText parseComponent(Component component) {
            List<RichTextSegment> segments = new ArrayList<RichTextSegment>();
            append(component, new EffectiveStyle(), segments);
            return new RichText(segments);
        }
    }
}
