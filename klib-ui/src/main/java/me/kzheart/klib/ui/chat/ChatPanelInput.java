package me.kzheart.klib.ui.chat;

import java.util.Objects;
import java.util.Optional;
import me.kzheart.klib.lang.RichText;
import me.kzheart.klib.scheduler.Ticks;
import me.kzheart.klib.ui.prompt.PromptParser;

/** 面板内的一次文本输入：提示、当前值、解析器与超时。解析器在异步聊天线程调用，只做纯解析。 */
public final class ChatPanelInput<T> {
    private final PromptParser<T> parser;
    private final RichText hint;
    private final String current;
    private final Ticks timeout;
    private final String cancelKeyword;
    private final boolean sign;

    private ChatPanelInput(PromptParser<T> parser, RichText hint, String current, Ticks timeout, String cancelKeyword, boolean sign) {
        this.parser = parser; this.hint = hint; this.current = current;
        this.timeout = timeout; this.cancelKeyword = cancelKeyword; this.sign = sign;
    }

    public static <T> ChatPanelInput<T> of(PromptParser<T> parser) {
        return new ChatPanelInput<T>(Objects.requireNonNull(parser, "parser"), null, "", Ticks.seconds(60), "cancel", true);
    }
    /** 非空白文本，最长 {@code maxLength} 个字符。 */
    public static ChatPanelInput<String> text(int maxLength) {
        if (maxLength < 1) throw new IllegalArgumentException("maxLength must be positive");
        return of(value -> value.trim().isEmpty() || value.length() > maxLength ? Optional.<String>empty() : Optional.of(value));
    }

    /** 显示在面板状态行的提示，例如“输入新的名称”。 */
    public ChatPanelInput<T> hint(RichText value) {
        return new ChatPanelInput<T>(parser, Objects.requireNonNull(value, "hint"), current, timeout, cancelKeyword, sign);
    }
    /** 用于聊天预填和告示牌预填的当前值。 */
    public ChatPanelInput<T> current(String value) {
        return new ChatPanelInput<T>(parser, hint, value == null ? "" : value, timeout, cancelKeyword, sign);
    }
    public ChatPanelInput<T> timeout(Ticks value) {
        return new ChatPanelInput<T>(parser, hint, current, Objects.requireNonNull(value, "timeout"), cancelKeyword, sign);
    }
    public ChatPanelInput<T> cancelKeyword(String value) {
        return new ChatPanelInput<T>(parser, hint, current, timeout, Objects.requireNonNull(value, "cancelKeyword"), sign);
    }
    /** 设为 false 时始终用聊天输入，适合多行或很长的内容。 */
    public ChatPanelInput<T> sign(boolean allowed) {
        return new ChatPanelInput<T>(parser, hint, current, timeout, cancelKeyword, allowed);
    }

    PromptParser<T> parser() { return parser; }
    RichText hint() { return hint; }
    String current() { return current; }
    Ticks timeout() { return timeout; }
    String cancelKeyword() { return cancelKeyword; }
    boolean signAllowed() { return sign; }
}
