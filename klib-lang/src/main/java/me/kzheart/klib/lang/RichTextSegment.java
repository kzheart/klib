package me.kzheart.klib.lang;

import java.util.Objects;

/** 不可变的已渲染文本片段。 */
public final class RichTextSegment {
    private final String text;
    private final MessageColor color;
    private final boolean bold;
    private final boolean italic;
    private final boolean underlined;
    private final boolean strikethrough;
    private final boolean obfuscated;
    private final TextAction hover;
    private final TextAction click;

    public RichTextSegment(
            String text,
            MessageColor color,
            boolean bold,
            TextAction hover,
            TextAction click
    ) {
        this(text, color, bold, false, false, false, false, hover, click);
    }

    public RichTextSegment(
            String text,
            MessageColor color,
            boolean bold,
            boolean italic,
            boolean underlined,
            boolean strikethrough,
            boolean obfuscated,
            TextAction hover,
            TextAction click
    ) {
        this.text = Objects.requireNonNull(text, "text");
        this.color = color;
        this.bold = bold;
        this.italic = italic;
        this.underlined = underlined;
        this.strikethrough = strikethrough;
        this.obfuscated = obfuscated;
        this.hover = hover;
        this.click = click;
    }

    public static RichTextSegment plain(String text) {
        return new RichTextSegment(text, null, false, null, null);
    }

    public String text() {
        return text;
    }

    public MessageColor color() {
        return color;
    }

    public boolean bold() {
        return bold;
    }

    public boolean italic() {
        return italic;
    }

    public boolean underlined() {
        return underlined;
    }

    public boolean strikethrough() {
        return strikethrough;
    }

    public boolean obfuscated() {
        return obfuscated;
    }

    public TextAction hover() {
        return hover;
    }

    public TextAction click() {
        return click;
    }

    RichTextSegment withText(String value) {
        return new RichTextSegment(value, color, bold, italic, underlined, strikethrough, obfuscated, hover, click);
    }
}
