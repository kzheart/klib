package me.kzheart.klib.lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 管线生成、不依赖 Adventure 的富消息。 */
public final class RichText {
    private final List<RichTextSegment> segments;

    public RichText(List<RichTextSegment> segments) {
        this.segments = Collections.unmodifiableList(new ArrayList<RichTextSegment>(segments));
    }

    public static RichText plain(String text) {
        return new RichText(Collections.singletonList(RichTextSegment.plain(text)));
    }

    public List<RichTextSegment> segments() {
        return segments;
    }

    public String plainText() {
        StringBuilder value = new StringBuilder();
        for (RichTextSegment segment : segments) {
            value.append(segment.text());
        }
        return value.toString();
    }

    public String legacyText() {
        StringBuilder value = new StringBuilder();
        RichTextSegment previous = null;
        for (RichTextSegment segment : segments) {
            if (previous != null && !sameStyle(previous, segment)) {
                value.append("\u00a7r");
            }
            if (previous == null || !sameStyle(previous, segment)) {
                appendStyle(value, segment);
            }
            value.append(segment.text());
            previous = segment;
        }
        return value.toString();
    }

    private static void appendStyle(StringBuilder output, RichTextSegment segment) {
        if (segment.color() != null) {
            output.append('\u00a7').append(segment.color().legacyCode());
        }
        if (segment.bold()) {
            output.append("\u00a7l");
        }
        if (segment.italic()) {
            output.append("\u00a7o");
        }
        if (segment.underlined()) {
            output.append("\u00a7n");
        }
        if (segment.strikethrough()) {
            output.append("\u00a7m");
        }
        if (segment.obfuscated()) {
            output.append("\u00a7k");
        }
    }

    private static boolean sameStyle(RichTextSegment left, RichTextSegment right) {
        return java.util.Objects.equals(left.color(), right.color())
                && left.bold() == right.bold()
                && left.italic() == right.italic()
                && left.underlined() == right.underlined()
                && left.strikethrough() == right.strikethrough()
                && left.obfuscated() == right.obfuscated();
    }
}
