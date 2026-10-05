package me.kzheart.klib.ui.chat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.ui.prompt.PromptSession;
import me.kzheart.klib.ui.prompt.PromptSpec;
import org.bukkit.entity.Player;

/** 单名玩家的面板会话；刷新会作废旧按钮，关闭仅取消该面板自己的输入提示。 */
public final class ChatPanelSession implements Disposable {
    final BukkitChatPanels renderer;
    final Player player;
    final Map<String, ChatPanelButton> actions = new LinkedHashMap<String, ChatPanelButton>();
    ChatPanel model;
    String revision = UUID.randomUUID().toString();
    long expires;
    int page;
    boolean closed;
    PromptSession<?> input;
    String invokingPermission = "";
    ChatPanelSession(BukkitChatPanels renderer, Player player, ChatPanel model, long now) {
        this.renderer = renderer; this.player = player; this.model = model;
        expires = now + model.lifetimeMillis();
    }
    public Player player() { return player; }
    public boolean isClosed() { return closed; }
    public int page() { return page; }
    public void page(int value) { renderer.render(this, model, value); }
    public void refresh(ChatPanel value) { renderer.render(this, value, page); }
    /** 提示和当前值均由业务文案提供；预填使用 suggest_command，不向公共聊天发送当前值。 */
    public <T> PromptSession<T> input(PromptSpec<T> spec, String currentValue,
                                      Consumer<T> accepted, Runnable cancelled) {
        return renderer.input(this, spec, currentValue, accepted, cancelled);
    }
    @Override public void dispose() { renderer.close(this); }
}
