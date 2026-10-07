package me.kzheart.klib.ui.chat;

import java.util.ArrayList;
import java.util.List;
import me.kzheart.klib.lang.RichText;
import me.kzheart.klib.lang.RichTextSegment;
import me.kzheart.klib.lang.MessageColor;
import me.kzheart.klib.lang.TextAction;

/** 原版字体的保守字宽估计；保留颜色、悬停、点击和代理对，资源包字体可调整 width。 */
final class ChatPanelLayout {
    private ChatPanelLayout() { }

    static int width(List<RichTextSegment> segments) {
        int total = 0;
        for (RichTextSegment segment : segments) {
            String value = segment.text();
            for (int i = 0; i < value.length();) {
                int cp = value.codePointAt(i); i += Character.charCount(cp);
                total += advance(cp, segment.bold());
            }
        }
        return total;
    }

    private static int advance(int cp, boolean bold) {
        int size;
        if (cp == ' ') size = 4;
        else if ("i!.,:;|'".indexOf(cp) >= 0) size = 2;
        else if ("ltI[]()".indexOf(cp) >= 0) size = 4;
        else if (cp < 128) size = 6;
        else size = 9;
        return size + (bold && cp != ' ' ? 1 : 0);
    }

    static List<RichText> wrap(RichText text, int limit) {
        List<RichText> rows = new ArrayList<RichText>();
        List<RichTextSegment> row = new ArrayList<RichTextSegment>();
        int width = 0;
        for (RichTextSegment segment : text.segments()) {
            String value = segment.text();
            StringBuilder part = new StringBuilder();
            for (int i = 0; i < value.length();) {
                int cp = value.codePointAt(i); i += Character.charCount(cp);
                int size = advance(cp, segment.bold());
                if (cp == '\n' || width + size > limit && width > 0) {
                    flush(row, part, segment);
                    rows.add(new RichText(row)); row = new ArrayList<RichTextSegment>(); width = 0;
                    if (cp == '\n') continue;
                }
                part.appendCodePoint(cp); width += size;
            }
            flush(row, part, segment);
        }
        rows.add(new RichText(row));
        return rows;
    }

    static List<RichText> limited(RichText text, int width, int count) {
        List<RichText> rows = wrap(text, width);
        if (rows.size() <= count) return rows;
        List<RichText> kept = new ArrayList<RichText>(rows.subList(0, count));
        List<RichTextSegment> last = new ArrayList<RichTextSegment>();
        last.add(new RichTextSegment("… 完整内容见悬停", MessageColor.GRAY, false,
                new TextAction(TextAction.Type.HOVER_TEXT, text.legacyText()), null));
        kept.set(count - 1, new RichText(last));
        return kept;
    }

    private static void flush(List<RichTextSegment> row, StringBuilder part, RichTextSegment source) {
        if (part.length() == 0) return;
        row.add(new RichTextSegment(part.toString(), source.color(), source.bold(), source.italic(), source.underlined(),
                source.strikethrough(), source.obfuscated(), source.hover(), source.click()));
        part.setLength(0);
    }
}
