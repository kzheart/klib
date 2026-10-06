package me.kzheart.klib.ui.chat;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import me.kzheart.klib.lang.RichText;
import org.bukkit.entity.Player;

/** 聊天面板的安装选项：整页行数、空闲有效期、发送方式、告示牌输入与内置文案。 */
public final class ChatPanelOptions {
    private final int lines;
    private final long idleMillis;
    private final BiConsumer<Player, RichText> sender;
    private final boolean signInput;
    private final ChatPanelSignInput signs;
    private final Map<ChatPanelText, RichText> texts;

    private ChatPanelOptions(Builder builder) {
        lines = builder.lines;
        idleMillis = builder.idleMillis;
        sender = builder.sender;
        signInput = builder.signInput;
        signs = builder.signs;
        texts = new EnumMap<ChatPanelText, RichText>(builder.texts);
    }

    public static ChatPanelOptions defaults() { return builder().build(); }
    public static Builder builder() { return new Builder(); }

    public int lines() { return lines; }
    public long idleMillis() { return idleMillis; }
    BiConsumer<Player, RichText> sender() { return sender; }
    boolean signInput() { return signInput; }
    ChatPanelSignInput signs() { return signs; }
    public RichText text(ChatPanelText key) {
        RichText value = texts.get(key);
        return value == null ? key.defaultValue() : value;
    }

    public static final class Builder {
        private int lines = 20;
        private long idleMillis = 900000L;
        private BiConsumer<Player, RichText> sender;
        private boolean signInput = true;
        private ChatPanelSignInput signs;
        private final Map<ChatPanelText, RichText> texts = new EnumMap<ChatPanelText, RichText>(ChatPanelText.class);
        private Builder() { }

        /** 每次发送的总行数，不足时补空行；默认 20 行，正好占满展开的聊天栏。 */
        public Builder lines(int value) {
            if (value < 8 || value > 100) throw new IllegalArgumentException("lines must be 8..100");
            lines = value; return this;
        }
        /** 无操作多久后会话过期；每次点击、输入或打开都会续期。默认 15 分钟。 */
        public Builder idleMillis(long value) {
            if (value < 10000L || value > 86400000L) throw new IllegalArgumentException("idle timeout must be 10s..24h");
            idleMillis = value; return this;
        }
        /** 自定义宿主的发送实现；默认复用 Lang 的 Bukkit 富文本桥接。 */
        public Builder sender(BiConsumer<Player, RichText> value) { sender = Objects.requireNonNull(value, "sender"); return this; }
        /** 是否提供告示牌输入；默认开启，服务端不支持时自动只用聊天输入。 */
        public Builder signInput(boolean enabled) { signInput = enabled; return this; }
        /** 指定告示牌输入实现，替代内置的 Paper 虚拟告示牌。 */
        public Builder signInput(ChatPanelSignInput value) { signs = Objects.requireNonNull(value, "signInput"); signInput = true; return this; }
        public Builder text(ChatPanelText key, RichText value) {
            texts.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value")); return this;
        }
        public ChatPanelOptions build() { return new ChatPanelOptions(this); }
    }
}
