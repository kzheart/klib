package me.kzheart.klib.ui.chat;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import me.kzheart.klib.lang.RichText;
import org.bukkit.entity.Player;

/**
 * 面板页面定义。内容由 {@link Builder#content} 的页面函数在每次显示时按实时状态构建，
 * 点击、输入完成和翻页后都会重新调用，不重放旧的模型。
 */
public final class ChatPanel {
    private final String id;
    private final RichText title;
    private final String permission;
    private final Predicate<Player> guard;
    private final int perLine;
    private final boolean once;
    private final Consumer<ChatPanelView> content;
    private final Consumer<ChatPanelSession> save;
    private final Consumer<ChatPanelSession> cancel;
    private final BiConsumer<ChatPanelSession, Throwable> errors;

    private ChatPanel(Builder builder) {
        id = builder.id;
        title = builder.title;
        permission = builder.permission;
        guard = builder.guard;
        perLine = builder.perLine;
        once = builder.once;
        content = builder.content;
        save = builder.save;
        cancel = builder.cancel;
        errors = builder.errors;
    }

    /**
     * @param id 页面 id，出现在按钮命令中；同一会话内同 id 视为同一页面，后打开的定义替换先前的定义，
     *           所以 id 应包含目标（如 {@code nbt:display.name}）
     */
    public static Builder builder(String id, RichText title) { return new Builder(id, title); }

    public String id() { return id; }
    public RichText title() { return title; }
    public String permission() { return permission; }
    int perLine() { return perLine; }
    boolean once() { return once; }
    Consumer<ChatPanelView> content() { return content; }
    boolean allowed(Player player) { return (permission.isEmpty() || player.hasPermission(permission)) && guard.test(player); }
    boolean hasHotkeys() { return save != null; }
    void hotkey(ChatPanelSession session, boolean cancelled) { (cancelled ? cancel : save).accept(session); }
    BiConsumer<ChatPanelSession, Throwable> errorHandler() { return errors; }

    public static final class Builder {
        private final String id;
        private final RichText title;
        private String permission = "";
        private Predicate<Player> guard = player -> true;
        private int perLine = 4;
        private boolean once;
        private Consumer<ChatPanelView> content = view -> { };
        private Consumer<ChatPanelSession> save;
        private Consumer<ChatPanelSession> cancel;
        private BiConsumer<ChatPanelSession, Throwable> errors;

        private Builder(String id, RichText title) {
            this.id = ChatPanelButton.checkId(id);
            this.title = Objects.requireNonNull(title, "title");
        }
        /** 页面函数：每次显示都调用，读取实时状态并声明分组、行和按钮。 */
        public Builder content(Consumer<ChatPanelView> value) { content = Objects.requireNonNull(value, "content"); return this; }
        public Builder permission(String value) { permission = Objects.requireNonNull(value, "permission"); return this; }
        /** 每次点击、显示与输入完成时在主线程检查；适合绑定实体、物品或业务版本。 */
        public Builder guard(Predicate<Player> value) { guard = Objects.requireNonNull(value, "guard"); return this; }
        /** {@link ChatPanelView#buttons} 每行默认放几个按钮，默认 4。 */
        public Builder perLine(int value) {
            if (value < 1 || value > 12) throw new IllegalArgumentException("perLine must be 1..12");
            perLine = value; return this;
        }
        /**
         * 一次性页面（确认框）：每次打开都使用新的地址，首个按钮动作执行后整页失效，
         * 旧聊天记录里的确认按钮不会被重复执行。
         */
        public Builder once() { once = true; return this; }
        /** 显式选择 F 保存、潜行+F 取消；未设置时完全不接管玩家换副手。 */
        public Builder hotkeys(Consumer<ChatPanelSession> onSave, Consumer<ChatPanelSession> onCancel) {
            save = Objects.requireNonNull(onSave, "onSave");
            cancel = Objects.requireNonNull(onCancel, "onCancel");
            return this;
        }
        /** 自定义动作失败时的处理，例如 {@code session.status(...)}；默认记录异常并在状态行显示通用失败提示。 */
        public Builder errorHandler(BiConsumer<ChatPanelSession, Throwable> handler) {
            errors = Objects.requireNonNull(handler, "errorHandler"); return this;
        }
        public ChatPanel build() { return new ChatPanel(this); }
    }
}
