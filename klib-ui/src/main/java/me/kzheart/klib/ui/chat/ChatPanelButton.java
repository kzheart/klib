package me.kzheart.klib.ui.chat;

import java.util.Objects;
import java.util.function.Consumer;
import me.kzheart.klib.lang.RichText;

/** 聊天按钮：业务回调、玩家命令、输入预填或剪贴板复制。 */
public final class ChatPanelButton {
    enum Kind { ACTION, COMMAND, SUGGEST, COPY }
    private final RichText label;
    private final RichText hover;
    private final String permission;
    private final Kind kind;
    private final String value;
    private final Consumer<ChatPanelSession> callback;

    private ChatPanelButton(RichText label, RichText hover, String permission, Kind kind,
                            String value, Consumer<ChatPanelSession> callback) {
        this.label = Objects.requireNonNull(label, "label");
        this.hover = hover;
        this.permission = permission;
        this.kind = kind;
        this.value = value;
        this.callback = callback;
    }

    public static ChatPanelButton action(RichText label, Consumer<ChatPanelSession> action) {
        return new ChatPanelButton(label, null, "", Kind.ACTION, "", Objects.requireNonNull(action, "action"));
    }
    /** 以玩家权限执行命令，不临时授予 OP，不以控制台代执行。 */
    public static ChatPanelButton command(RichText label, String command) {
        return new ChatPanelButton(label, null, "", Kind.COMMAND, Objects.requireNonNull(command, "command"), null);
    }
    public static ChatPanelButton suggest(RichText label, String value) {
        return new ChatPanelButton(label, null, "", Kind.SUGGEST, Objects.requireNonNull(value, "value"), null);
    }
    public static ChatPanelButton copy(RichText label, String value) {
        return new ChatPanelButton(label, null, "", Kind.COPY, Objects.requireNonNull(value, "value"), null);
    }
    public ChatPanelButton hover(RichText text) {
        return new ChatPanelButton(label, Objects.requireNonNull(text, "hover"), permission, kind, value, callback);
    }
    public ChatPanelButton permission(String required) {
        return new ChatPanelButton(label, hover, Objects.requireNonNull(required, "permission"), kind, value, callback);
    }
    public RichText label() { return label; }
    public RichText hover() { return hover; }
    public String permission() { return permission; }
    Kind kind() { return kind; }
    String value() { return value; }
    void invoke(ChatPanelSession session) {
        if (kind == Kind.ACTION) callback.accept(session);
        else if (kind == Kind.COMMAND) session.player().performCommand(value.startsWith("/") ? value.substring(1) : value);
    }
}
