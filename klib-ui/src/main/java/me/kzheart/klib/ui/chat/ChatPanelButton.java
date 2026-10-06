package me.kzheart.klib.ui.chat;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import me.kzheart.klib.lang.RichText;

/**
 * 聊天按钮。action 与 command 按钮通过“页面 id + 按钮 id”路由，点击时按实时状态重建页面后再查找，
 * 因此聊天记录里的旧按钮同样可用；suggest 与 copy 只在客户端生效。
 */
public final class ChatPanelButton {
    static final Pattern ID = Pattern.compile("[A-Za-z0-9_.:+-]{1,64}");
    enum Kind { ACTION, COMMAND, SUGGEST, COPY }
    private final String id;
    private final RichText label;
    private final RichText hover;
    private final RichText value;
    private final String permission;
    private final Kind kind;
    private final String text;
    private final Consumer<ChatPanelSession> callback;
    private final Consumer<ChatPanelSession> reset;

    private ChatPanelButton(String id, RichText label, RichText hover, RichText value, String permission, Kind kind,
                            String text, Consumer<ChatPanelSession> callback, Consumer<ChatPanelSession> reset) {
        this.id = id;
        this.label = Objects.requireNonNull(label, "label");
        this.hover = hover;
        this.value = value;
        this.permission = permission;
        this.kind = kind;
        this.text = text;
        this.callback = callback;
        this.reset = reset;
    }

    /**
     * 业务回调按钮。id 在页面内唯一，应由按钮指向的目标决定（如 {@code lore.3.edit}），
     * 内容变化后 id 也应变化，避免旧按钮作用到新内容。
     */
    public static ChatPanelButton action(String id, RichText label, Consumer<ChatPanelSession> action) {
        return new ChatPanelButton(checkId(id), label, null, null, "", Kind.ACTION, "", Objects.requireNonNull(action, "action"), null);
    }
    /** 以玩家权限执行命令后刷新面板；不临时授予 OP，不以控制台代执行。 */
    public static ChatPanelButton command(RichText label, String command) {
        String value = Objects.requireNonNull(command, "command");
        return new ChatPanelButton("cmd." + Integer.toHexString(value.hashCode()), label, null, null, "", Kind.COMMAND, value, null, null);
    }
    public static ChatPanelButton suggest(RichText label, String value) {
        return new ChatPanelButton(null, label, null, null, "", Kind.SUGGEST, Objects.requireNonNull(value, "value"), null, null);
    }
    /** 复制到剪贴板；旧版客户端不支持时改用 {@link #suggest}。 */
    public static ChatPanelButton copy(RichText label, String value) {
        return new ChatPanelButton(null, label, null, null, "", Kind.COPY, Objects.requireNonNull(value, "value"), null, null);
    }

    public ChatPanelButton hover(RichText value) {
        return new ChatPanelButton(id, label, Objects.requireNonNull(value, "hover"), this.value, permission, kind, text, callback, reset);
    }
    /** 直接显示在按钮上的当前值，如 {@code [飞行速度 0.2]}。 */
    public ChatPanelButton value(RichText value) {
        return new ChatPanelButton(id, label, hover, Objects.requireNonNull(value, "value"), permission, kind, text, callback, reset);
    }
    public ChatPanelButton value(String value) { return value(RichText.plain(value)); }
    /** 无权限时按钮隐藏，点击旧按钮时再次校验并提示。 */
    public ChatPanelButton permission(String required) {
        return new ChatPanelButton(id, label, hover, value, Objects.requireNonNull(required, "permission"), kind, text, callback, reset);
    }
    /** 在按钮内追加 (R) 重置入口，与按钮共用权限。 */
    public ChatPanelButton reset(Consumer<ChatPanelSession> action) {
        if (kind != Kind.ACTION) throw new IllegalStateException("only action buttons can be reset");
        return new ChatPanelButton(id, label, hover, value, permission, kind, text, callback, Objects.requireNonNull(action, "reset"));
    }

    public String id() { return id; }
    public RichText label() { return label; }
    public RichText hover() { return hover; }
    public RichText value() { return value; }
    public String permission() { return permission; }
    Kind kind() { return kind; }
    String text() { return text; }
    boolean routed() { return kind == Kind.ACTION || kind == Kind.COMMAND; }
    Consumer<ChatPanelSession> resetAction() { return reset; }
    void invoke(ChatPanelSession session) {
        if (kind == Kind.ACTION) callback.accept(session);
        else if (kind == Kind.COMMAND) session.player().performCommand(text.startsWith("/") ? text.substring(1) : text);
    }

    static String checkId(String id) {
        Objects.requireNonNull(id, "id");
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("invalid chat panel id: " + id);
        return id;
    }
}
