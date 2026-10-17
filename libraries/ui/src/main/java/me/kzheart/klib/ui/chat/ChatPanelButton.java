package me.kzheart.klib.ui.chat;

import java.util.function.Consumer;
import java.util.regex.Pattern;
import me.kzheart.klib.lang.RichText;

/** 按钮的 Java 平台返回契约；不可变状态、路由和回调由 Kotlin 实现。
 * 旧 Kotlin 对 id()/hover()/value() 判空后重复取值依赖平台类型；可选按钮字段仍返回 null。
 */
public final class ChatPanelButton {
    static final Pattern ID = ChatPanelButtonValue.ID;
    enum Kind { ACTION, COMMAND, SUGGEST, COPY }
    private final ChatPanelButtonValue state;
    private ChatPanelButton(ChatPanelButtonValue state) { this.state = state; }
    public static ChatPanelButton action(String id, RichText label, Consumer<ChatPanelSession> action) { return new ChatPanelButton(ChatPanelButtonValue.action(id, label, action)); }
    public static ChatPanelButton command(RichText label, String command) { return new ChatPanelButton(ChatPanelButtonValue.command(label, command)); }
    public static ChatPanelButton suggest(RichText label, String value) { return new ChatPanelButton(ChatPanelButtonValue.suggest(label, value)); }
    public static ChatPanelButton copy(RichText label, String value) { return new ChatPanelButton(ChatPanelButtonValue.copy(label, value)); }
    public ChatPanelButton hover(RichText value) { return new ChatPanelButton(state.hover(value)); }
    public ChatPanelButton value(RichText value) { return new ChatPanelButton(state.value(value)); }
    public ChatPanelButton value(String value) { return new ChatPanelButton(state.value(value)); }
    public ChatPanelButton permission(String required) { return new ChatPanelButton(state.permission(required)); }
    public ChatPanelButton reset(Consumer<ChatPanelSession> action) { return new ChatPanelButton(state.reset(action)); }
    public String id() { return state.id(); }
    public RichText label() { return state.label(); }
    public RichText hover() { return state.hover(); }
    public RichText value() { return state.value(); }
    public String permission() { return state.permission(); }
    Kind kind() { return state.kind(); }
    String text() { return state.text(); }
    boolean routed() { return state.routed(); }
    Consumer<ChatPanelSession> resetAction() { return state.resetAction(); }
    void invoke(ChatPanelSession session) { state.invoke(session); }
    static String checkId(String id) { return ChatPanelButtonValue.checkId(id); }
}
