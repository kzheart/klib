package me.kzheart.klib.ui.chat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import me.kzheart.klib.lang.RichText;
import org.bukkit.entity.Player;

/** 不可变面板描述；行可作为标题、分组、字段或列表项，业务数据由调用方提供。 */
public final class ChatPanel {
    public static final class Row {
        private final RichText text;
        private final List<ChatPanelButton> buttons;
        private Row(RichText text, ChatPanelButton[] buttons) {
            this.text = Objects.requireNonNull(text, "text");
            this.buttons = Collections.unmodifiableList(new ArrayList<ChatPanelButton>(Arrays.asList(buttons)));
            for (ChatPanelButton button : this.buttons) Objects.requireNonNull(button, "button");
        }
        public RichText text() { return text; }
        public List<ChatPanelButton> buttons() { return buttons; }
    }

    private final RichText title;
    private final List<Row> rows;
    private final List<ChatPanelButton> footer;
    private final String permission;
    private final Predicate<Player> guard;
    private final int pageSize;
    private final int clearLines;
    private final long lifetimeMillis;
    private final Consumer<ChatPanelSession> save;
    private final Consumer<ChatPanelSession> cancel;
    private final BiConsumer<ChatPanelSession, Throwable> errors;

    private ChatPanel(Builder builder) {
        title = builder.title;
        rows = Collections.unmodifiableList(new ArrayList<Row>(builder.rows));
        footer = Collections.unmodifiableList(new ArrayList<ChatPanelButton>(builder.footer));
        permission = builder.permission;
        guard = builder.guard;
        pageSize = builder.pageSize;
        clearLines = builder.clearLines;
        lifetimeMillis = builder.lifetimeMillis;
        save = builder.save;
        cancel = builder.cancel;
        errors = builder.errors;
    }
    public static Builder builder(RichText title) { return new Builder(title); }
    public RichText title() { return title; }
    public List<Row> rows() { return rows; }
    public List<ChatPanelButton> footer() { return footer; }
    public String permission() { return permission; }
    public int pageSize() { return pageSize; }
    public int clearLines() { return clearLines; }
    public long lifetimeMillis() { return lifetimeMillis; }
    boolean allowed(Player player) { return (permission.isEmpty() || player.hasPermission(permission)) && guard.test(player); }
    boolean hasHotkeys() { return save != null && cancel != null; }
    void hotkey(ChatPanelSession session, boolean cancelled) { (cancelled ? cancel : save).accept(session); }
    void failure(ChatPanelSession session, Throwable error) { if (errors != null) errors.accept(session, error); }

    public static final class Builder {
        private final RichText title;
        private final List<Row> rows = new ArrayList<Row>();
        private final List<ChatPanelButton> footer = new ArrayList<ChatPanelButton>();
        private String permission = "";
        private Predicate<Player> guard = player -> true;
        private int pageSize = 8;
        private int clearLines;
        private long lifetimeMillis = 120000L;
        private Consumer<ChatPanelSession> save;
        private Consumer<ChatPanelSession> cancel;
        private BiConsumer<ChatPanelSession, Throwable> errors;
        private Builder(RichText title) { this.title = Objects.requireNonNull(title, "title"); }
        public Builder row(RichText text, ChatPanelButton... buttons) {
            rows.add(new Row(text, Objects.requireNonNull(buttons, "buttons")));
            return this;
        }
        public Builder footer(ChatPanelButton... buttons) {
            for (ChatPanelButton button : buttons) footer.add(Objects.requireNonNull(button, "button"));
            return this;
        }
        public Builder permission(String value) { permission = Objects.requireNonNull(value, "permission"); return this; }
        /** 每次点击、刷新与输入完成时主线程重新检查；适合绑定实体、物品或业务版本。 */
        public Builder guard(Predicate<Player> value) { guard = Objects.requireNonNull(value, "guard"); return this; }
        public Builder pageSize(int value) {
            if (value < 1 || value > 20) throw new IllegalArgumentException("pageSize must be 1..20");
            pageSize = value; return this;
        }
        public Builder clearLines(int value) {
            if (value < 0 || value > 100) throw new IllegalArgumentException("clearLines must be 0..100");
            clearLines = value; return this;
        }
        public Builder lifetimeMillis(long value) {
            if (value < 1000L || value > 3600000L) throw new IllegalArgumentException("lifetime must be 1s..1h");
            lifetimeMillis = value; return this;
        }
        /** 显式选择 F 保存、潜行+F 取消；未设置时完全不接管玩家换副手。 */
        public Builder hotkeys(Consumer<ChatPanelSession> onSave, Consumer<ChatPanelSession> onCancel) {
            save = Objects.requireNonNull(onSave, "onSave");
            cancel = Objects.requireNonNull(onCancel, "onCancel");
            return this;
        }
        /** 默认只记录异常；业务自行决定错误提示与界面处理。 */
        public Builder errorHandler(BiConsumer<ChatPanelSession, Throwable> handler) {
            errors = Objects.requireNonNull(handler, "errorHandler"); return this;
        }
        public ChatPanel build() { return new ChatPanel(this); }
    }
}
