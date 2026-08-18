package me.kzheart.klib.command;

import me.kzheart.klib.lang.MessageColor;
import me.kzheart.klib.lang.RichText;
import me.kzheart.klib.lang.RichTextSegment;

import java.util.ArrayList;
import java.util.List;

/** 将带 legacy 颜色码（{@code §x}）的文本解析为 RichText 片段。 */
final class LegacyTextParser {
    private LegacyTextParser() {
    }

    static RichText parse(String source) {
        List<RichTextSegment> segments = new ArrayList<RichTextSegment>();
        StringBuilder text = new StringBuilder();
        MessageColor color = null;
        boolean bold = false;
        boolean italic = false;
        boolean underlined = false;
        boolean strikethrough = false;
        boolean obfuscated = false;
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '§' && index + 1 < source.length()) {
                char code = Character.toLowerCase(source.charAt(index + 1));
                MessageColor mapped = colorOf(code);
                boolean recognized = mapped != null || isStyleCode(code);
                if (recognized) {
                    if (text.length() > 0) {
                        segments.add(new RichTextSegment(
                                text.toString(),
                                color,
                                bold,
                                italic,
                                underlined,
                                strikethrough,
                                obfuscated,
                                null,
                                null));
                        text.setLength(0);
                    }
                    if (mapped != null) {
                        color = mapped;
                        bold = false;
                        italic = false;
                        underlined = false;
                        strikethrough = false;
                        obfuscated = false;
                    } else if (code == 'r') {
                        color = null;
                        bold = false;
                        italic = false;
                        underlined = false;
                        strikethrough = false;
                        obfuscated = false;
                    } else if (code == 'l') {
                        bold = true;
                    } else if (code == 'o') {
                        italic = true;
                    } else if (code == 'n') {
                        underlined = true;
                    } else if (code == 'm') {
                        strikethrough = true;
                    } else {
                        obfuscated = true;
                    }
                    index += 2;
                    continue;
                }
            }
            text.append(current);
            index++;
        }
        if (text.length() > 0 || segments.isEmpty()) {
            segments.add(new RichTextSegment(
                    text.toString(),
                    color,
                    bold,
                    italic,
                    underlined,
                    strikethrough,
                    obfuscated,
                    null,
                    null));
        }
        return new RichText(segments);
    }

    private static boolean isStyleCode(char code) {
        return code == 'l' || code == 'o' || code == 'n' || code == 'm'
                || code == 'k' || code == 'r';
    }

    private static MessageColor colorOf(char code) {
        switch (code) {
            case '0': return MessageColor.BLACK;
            case '1': return MessageColor.DARK_BLUE;
            case '2': return MessageColor.DARK_GREEN;
            case '3': return MessageColor.DARK_AQUA;
            case '4': return MessageColor.DARK_RED;
            case '5': return MessageColor.DARK_PURPLE;
            case '6': return MessageColor.GOLD;
            case '7': return MessageColor.GRAY;
            case '8': return MessageColor.DARK_GRAY;
            case '9': return MessageColor.BLUE;
            case 'a': return MessageColor.GREEN;
            case 'b': return MessageColor.AQUA;
            case 'c': return MessageColor.RED;
            case 'd': return MessageColor.LIGHT_PURPLE;
            case 'e': return MessageColor.YELLOW;
            case 'f': return MessageColor.WHITE;
            default: return null;
        }
    }
}
