package me.kzheart.klib.ui.chat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.kzheart.klib.lang.MessageColor;
import me.kzheart.klib.lang.RichText;
import me.kzheart.klib.lang.RichTextSegment;
import me.kzheart.klib.lang.TextAction;
import org.bukkit.entity.Player;

/**
 * 页面函数每次显示时拿到的构建器。分组会尽量整组留在同一页；按钮渲染为 {@code [名称 值 (R)]}，
 * 玩家无权限的按钮不显示。
 */
public final class ChatPanelView {
    static final String INDENT = "  ";
    private final ChatPanelSession session;
    private final String pageKey;
    private final String command;
    private final ChatPanelOptions options;
    private final int perLine;
    private RichText subtitle;
    private List<RichText> block;
    final List<List<RichText>> blocks = new ArrayList<List<RichText>>();
    final List<ChatPanelButton> footer = new ArrayList<ChatPanelButton>();
    final Map<String, Route> routes = new HashMap<String, Route>();

    ChatPanelView(ChatPanelSession session, String pageKey, String command, ChatPanelOptions options, int perLine) {
        this.session = session; this.pageKey = pageKey; this.command = command;
        this.options = options; this.perLine = perLine;
    }

    public Player player() { return session.player(); }
    public ChatPanelSession session() { return session; }

    /** 显示在标题后的副标题，例如当前编辑的目标。 */
    public ChatPanelView subtitle(RichText value) { subtitle = Objects.requireNonNull(value, "subtitle"); return this; }

    /** 开始一个分组；分组标题显示为灰色 {@code 名称 ···}。 */
    public ChatPanelView group(String title) {
        return group(new RichText(Arrays.asList(new RichTextSegment(title, MessageColor.DARK_GRAY, false, null, null),
                new RichTextSegment(" ···", MessageColor.DARK_GRAY, false, null, null))));
    }
    public ChatPanelView group(RichText title) {
        block = new ArrayList<RichText>();
        blocks.add(block);
        add(title.segments());
        return this;
    }
    public ChatPanelView text(RichText text) { add(text.segments()); return this; }
    public ChatPanelView blank() { current().add(RichText.plain("")); return this; }

    /** 一行文字后接若干按钮。 */
    public ChatPanelView line(RichText text, ChatPanelButton... buttons) {
        List<RichText> rows = ChatPanelLayout.wrap(text, options.width() - 8);
        for (int i = 0; i < rows.size() - 1; i++) add(rows.get(i).segments());
        List<RichTextSegment> line = new ArrayList<RichTextSegment>(rows.get(rows.size() - 1).segments());
        for (ChatPanelButton button : buttons) {
            if (!register(button)) continue;
            List<RichTextSegment> rendered = render(button);
            if (!line.isEmpty() && ChatPanelLayout.width(line) + 4 + ChatPanelLayout.width(rendered) > options.width() - 8) {
                add(line); line = new ArrayList<RichTextSegment>();
            }
            if (!line.isEmpty()) line.add(RichTextSegment.plain(" "));
            line.addAll(rendered);
        }
        add(line);
        return this;
    }

    /** 按页面的 perLine 自动换行排列按钮。 */
    public ChatPanelView buttons(ChatPanelButton... buttons) { return buttons(perLine, Arrays.asList(buttons)); }
    public ChatPanelView buttons(List<ChatPanelButton> buttons) { return buttons(perLine, buttons); }
    public ChatPanelView buttons(int perLine, ChatPanelButton... buttons) { return buttons(perLine, Arrays.asList(buttons)); }
    public ChatPanelView buttons(int perLine, List<ChatPanelButton> buttons) {
        if (perLine < 1) throw new IllegalArgumentException("perLine must be positive");
        List<RichTextSegment> line = new ArrayList<RichTextSegment>();
        int count = 0;
        for (ChatPanelButton button : buttons) {
            if (!register(button)) continue;
            List<RichTextSegment> rendered = render(button);
            if (!line.isEmpty() && (count >= perLine || ChatPanelLayout.width(line) + 4 + ChatPanelLayout.width(rendered) > options.width() - 8)) {
                add(line); line = new ArrayList<RichTextSegment>(); count = 0;
            }
            if (!line.isEmpty()) line.add(RichTextSegment.plain(" "));
            line.addAll(rendered);
            count++;
        }
        if (!line.isEmpty()) add(line);
        return this;
    }

    /** 追加到底部导航行（返回、关闭之前）。 */
    public ChatPanelView footer(ChatPanelButton... buttons) {
        for (ChatPanelButton button : buttons) if (register(button)) footer.add(button);
        return this;
    }

    RichText subtitle() { return subtitle; }

    private void add(List<RichTextSegment> segments) {
        for (RichText row : ChatPanelLayout.wrap(new RichText(segments), options.width() - 8)) current().add(indent(row.segments()));
    }

    private List<RichText> current() {
        if (block == null) { block = new ArrayList<RichText>(); blocks.add(block); }
        return block;
    }

    /** 登记路由；返回按钮是否对当前玩家可见。 */
    private boolean register(ChatPanelButton button) {
        Objects.requireNonNull(button, "button");
        if (button.routed()) {
            put(button.id(), new Route(button, false));
            if (button.resetAction() != null) put(button.id() + "!r", new Route(button, true));
        }
        return button.permission().isEmpty() || session.player().hasPermission(button.permission());
    }
    private void put(String id, Route route) {
        Route previous = routes.put(id, route);
        if (previous != null && !(previous.button.kind() == ChatPanelButton.Kind.COMMAND && previous.button.text().equals(route.button.text())))
            throw new IllegalStateException("duplicate chat panel button id: " + id);
    }

    List<RichTextSegment> render(ChatPanelButton button) {
        TextAction click;
        if (button.kind() == ChatPanelButton.Kind.COPY) click = new TextAction(TextAction.Type.COPY_TO_CLIPBOARD, button.text());
        else if (button.kind() == ChatPanelButton.Kind.SUGGEST) click = new TextAction(TextAction.Type.SUGGEST_COMMAND, button.text());
        else click = run(button.id());
        List<RichTextSegment> out = new ArrayList<RichTextSegment>();
        TextAction hover = hover(button.hover());
        out.add(new RichTextSegment("[", MessageColor.DARK_GRAY, false, hover, click));
        out.addAll(decorate(button.label().segments(), MessageColor.GOLD, hover, click));
        if (button.value() != null) {
            out.add(new RichTextSegment(" ", null, false, hover, click));
            out.addAll(decorate(button.value().segments(), MessageColor.WHITE, hover, click));
        }
        if (button.resetAction() != null) {
            out.add(RichTextSegment.plain(" "));
            out.add(new RichTextSegment("(R)", MessageColor.RED, false, hover(options.text(ChatPanelText.RESET)), run(button.id() + "!r")));
        }
        out.add(new RichTextSegment("]", MessageColor.DARK_GRAY, false, hover, click));
        return out;
    }

    TextAction run(String action) { return new TextAction(TextAction.Type.RUN_COMMAND, command + " " + pageKey + " " + action); }

    static TextAction hover(RichText text) { return text == null ? null : new TextAction(TextAction.Type.HOVER_TEXT, text.legacyText()); }

    static List<RichTextSegment> decorate(List<RichTextSegment> segments, MessageColor fallback, TextAction hover, TextAction click) {
        List<RichTextSegment> out = new ArrayList<RichTextSegment>();
        for (RichTextSegment text : segments) out.add(new RichTextSegment(text.text(), text.color() == null ? fallback : text.color(),
                text.bold(), text.italic(), text.underlined(), text.strikethrough(), text.obfuscated(),
                hover == null ? text.hover() : hover, click == null ? text.click() : click));
        return out;
    }

    static RichText indent(List<RichTextSegment> segments) {
        List<RichTextSegment> line = new ArrayList<RichTextSegment>(segments.size() + 1);
        line.add(RichTextSegment.plain(INDENT));
        line.addAll(segments);
        return new RichText(line);
    }

    List<List<RichText>> blocks() { return Collections.unmodifiableList(blocks); }

    static final class Route {
        final ChatPanelButton button;
        final boolean reset;
        Route(ChatPanelButton button, boolean reset) { this.button = button; this.reset = reset; }
    }
}
