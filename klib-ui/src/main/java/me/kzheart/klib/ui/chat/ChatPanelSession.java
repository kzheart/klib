package me.kzheart.klib.ui.chat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import me.kzheart.klib.lang.RichText;
import me.kzheart.klib.scheduler.TaskHandle;
import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.ui.prompt.PromptSession;
import org.bukkit.entity.Player;

/**
 * 单名玩家的面板会话，记录打开过的页面、返回路径、状态行和进行中的输入。
 * 所有方法要求主线程；动作执行后面板在下一 tick 按实时状态重画当前页。
 */
public final class ChatPanelSession implements Disposable {
    static final int MAX_PAGES = 64;
    final BukkitChatPanels panels;
    final Player player;
    final Map<String, Page> pages = new LinkedHashMap<String, Page>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Page> eldest) { return size() > MAX_PAGES; }
    };
    final Deque<String> history = new ArrayDeque<String>();
    String current;
    RichText status;
    long lastActive;
    boolean closed;
    boolean suspended;
    boolean rendering;
    int renders;
    int acting;
    int navigation;
    Pending<?> pending;
    TaskHandle redraw;
    String invokingPermission = "";

    ChatPanelSession(BukkitChatPanels panels, Player player, long now) {
        this.panels = panels; this.player = player; lastActive = now;
    }

    public Player player() { return player; }
    public boolean isClosed() { return closed; }
    /** 当前页面的 id。 */
    public String panelId() { Page page = pages.get(current); return page == null ? "" : page.panel.id(); }
    /** 当前页面内的分页序号，从 0 开始。 */
    public int page() { Page page = pages.get(current); return page == null ? 0 : page.index; }
    public void page(int index) { panels.page(this, index); }
    /** 进入另一个页面，当前页面加入返回路径。 */
    public void open(ChatPanel panel) { panels.navigate(this, Objects.requireNonNull(panel, "panel")); }
    /** 回到上一个页面；没有上一页时返回 false。 */
    public boolean back() { return panels.back(this); }
    /** 安排下一 tick 按实时状态重画当前页。 */
    public void refresh() { panels.scheduleRedraw(this); }
    /** 设置状态行，在下次重画时显示在导航行上方，直到下一次操作。 */
    public void status(RichText value) { status = Objects.requireNonNull(value, "status"); panels.scheduleRedraw(this); }
    public void status(String value) { status(RichText.plain(value)); }
    public boolean inputting() { return pending != null; }

    /** 开始输入；面板保持显示，提交或取消后回到发起输入的页面。 */
    public <T> void input(ChatPanelInput<T> input, Consumer<T> accepted) { panels.input(this, input, accepted, null); }
    /** 同上；取消或超时时额外调用 {@code cancelled}。 */
    public <T> void input(ChatPanelInput<T> input, Consumer<T> accepted, Runnable cancelled) {
        panels.input(this, input, accepted, Objects.requireNonNull(cancelled, "cancelled"));
    }

    /** 暂停整页刷新，显示原业务的命令/多行输出，并追加返回入口；不会关闭会话或丢失字段值。 */
    public void output(Runnable action) { panels.output(this, Objects.requireNonNull(action, "action")); }

    /** 关闭面板并用空行顶掉聊天栏里的面板文字。 */
    @Override public void dispose() { panels.close(this, true); }

    static final class Page {
        final String key;
        ChatPanel panel;
        int index;
        boolean retired;
        Page(String key, ChatPanel panel) { this.key = key; this.panel = panel; }
    }

    static final class Pending<T> {
        final ChatPanelInput<T> input;
        final Consumer<T> accepted;
        final Runnable cancelled;
        final String origin;
        final String permission;
        PromptSession<T> prompt;
        boolean sign;
        TaskHandle timeout;
        Pending(ChatPanelInput<T> input, Consumer<T> accepted, Runnable cancelled, String origin, String permission) {
            this.input = input; this.accepted = accepted; this.cancelled = cancelled;
            this.origin = origin; this.permission = permission;
        }
    }
}
