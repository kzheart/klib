package me.kzheart.klib.lang;

/** 不可变的已渲染文本片段；可选样式访问器保留 Java 平台返回类型。 */
public final class RichTextSegment {
    private final RichTextSegmentValue value;

    public RichTextSegment(String text, MessageColor color, boolean bold, TextAction hover, TextAction click) {
        this(text, color, bold, false, false, false, false, hover, click);
    }

    public RichTextSegment(String text, MessageColor color, boolean bold, boolean italic,
                           boolean underlined, boolean strikethrough, boolean obfuscated,
                           TextAction hover, TextAction click) {
        this(new RichTextSegmentValue(text, color, bold, italic, underlined, strikethrough, obfuscated, hover, click));
    }

    private RichTextSegment(RichTextSegmentValue value) { this.value = value; }

    public static RichTextSegment plain(String text) { return new RichTextSegment(text, null, false, null, null); }
    public String text() { return value.getText(); }
    public MessageColor color() { return value.getColor(); }
    public boolean bold() { return value.getBold(); }
    public boolean italic() { return value.getItalic(); }
    public boolean underlined() { return value.getUnderlined(); }
    public boolean strikethrough() { return value.getStrikethrough(); }
    public boolean obfuscated() { return value.getObfuscated(); }
    public TextAction hover() { return value.getHover(); }
    public TextAction click() { return value.getClick(); }
    RichTextSegment withText(String text) { return new RichTextSegment(value.withText(text)); }
}
