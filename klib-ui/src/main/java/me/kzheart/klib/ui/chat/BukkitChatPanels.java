package me.kzheart.klib.ui.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import me.kzheart.klib.KLogger;
import me.kzheart.klib.command.Arg;
import me.kzheart.klib.command.Arguments;
import me.kzheart.klib.lang.BukkitMessageRouter;
import me.kzheart.klib.lang.MessageRecipient;
import me.kzheart.klib.lang.RichText;
import me.kzheart.klib.lang.RichTextSegment;
import me.kzheart.klib.lang.TextAction;
import me.kzheart.klib.scheduler.Ticks;
import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.Scope;
import me.kzheart.klib.ui.prompt.BukkitChatPrompts;
import me.kzheart.klib.ui.prompt.PromptSession;
import me.kzheart.klib.ui.prompt.PromptSpec;
import me.kzheart.klib.ui.prompt.PromptStatus;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.Plugin;

/** 作用域持有的聊天面板。所有按钮通过启动声明的命令分派，UUID 回调只属于当前玩家和当前页。 */
public final class BukkitChatPanels implements Listener, Disposable {
    private final Scope owner;
    private final Plugin plugin;
    private final String command;
    private final BukkitChatPrompts prompts;
    private final BiConsumer<Player, RichText> sender;
    private final KLogger logger;
    private final LongSupplier clock;
    private final Map<UUID, ChatPanelSession> sessions = new HashMap<UUID, ChatPanelSession>();
    private boolean disposed;

    BukkitChatPanels(Scope owner, Plugin plugin, String command, BukkitChatPrompts prompts,
                      BiConsumer<Player, RichText> sender, LongSupplier clock) {
        this.owner = owner; this.plugin = plugin; this.command = command;
        this.prompts = prompts; this.sender = sender; this.clock = clock;
        logger = owner.findCapability(KLogger.class).orElseGet(() -> new KLogger(plugin.getLogger()));
    }

    /** 调用方先安装 CommandModule 和 BukkitChatPrompts；必须在插件启动阶段安装一次。 */
    public static BukkitChatPanels install(Scope owner, Plugin plugin, String command, BukkitChatPrompts prompts) {
        BukkitMessageRouter router = owner.install(new BukkitMessageRouter(plugin.getServer()));
        return install(owner, plugin, command, prompts,
                (player, message) -> router.route(MessageRecipient.of(player, false), message));
    }

    public static BukkitChatPanels install(Scope owner, Plugin plugin, String command,
                                            BukkitChatPrompts prompts, BiConsumer<Player, RichText> sender) {
        Objects.requireNonNull(owner, "owner"); Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(prompts, "prompts"); Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(command, "command");
        if (!command.matches("[a-z][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("invalid chat panel command label");
        if (!Bukkit.isPrimaryThread() || owner.isClosed()) throw new IllegalStateException("install chat panels at plugin startup on the main thread");
        BukkitChatPanels panels = owner.install(new BukkitChatPanels(owner, plugin, command, prompts, sender, System::currentTimeMillis));
        Arg<String> revision = Arguments.string("revision");
        Arg<String> action = Arguments.string("action");
        owner.command(command, root -> root.playerOnly().argument(revision).argument(action)
                .executes(context -> panels.dispatch((Player) context.sender(), context.get(revision), context.get(action))));
        plugin.getServer().getPluginManager().registerEvents(panels, plugin);
        owner.every(Ticks.seconds(1), panels::prune);
        return panels;
    }

    public ChatPanelSession open(Player player, ChatPanel model) {
        requireMain(); Objects.requireNonNull(model, "model"); Objects.requireNonNull(player, "player");
        ensureOpen();
        if (!current(player) || !model.allowed(player)) throw new IllegalStateException("chat panel access denied or target changed");
        ChatPanelSession previous = sessions.get(player.getUniqueId());
        if (previous != null) close(previous);
        ChatPanelSession session = new ChatPanelSession(this, player, model, clock.getAsLong());
        sessions.put(player.getUniqueId(), session);
        try { render(session, model, 0); }
        catch (RuntimeException failure) { close(session); throw failure; }
        return session;
    }

    void render(ChatPanelSession session, ChatPanel model, int requested) {
        requireMain(); Objects.requireNonNull(model, "model");
        if (!active(session) || !model.allowed(session.player)) { close(session); return; }
        if (session.input != null) { PromptSession<?> old = session.input; session.input = null; old.cancel(); }
        session.model = model;
        int pages = Math.max(1, (model.rows().size() + model.pageSize() - 1) / model.pageSize());
        session.page = Math.max(0, Math.min(requested, pages - 1));
        invalidate(session);
        for (int i = 0; i < model.clearLines(); i++) sender.accept(session.player, RichText.plain(" "));
        sender.accept(session.player, model.title());
        int from = session.page * model.pageSize();
        for (int i = from; i < Math.min(model.rows().size(), from + model.pageSize()); i++) {
            ChatPanel.Row row = model.rows().get(i);
            List<RichTextSegment> line = new ArrayList<RichTextSegment>(row.text().segments());
            for (ChatPanelButton button : row.buttons()) append(session, line, button);
            sender.accept(session.player, new RichText(line));
        }
        List<RichTextSegment> footer = new ArrayList<RichTextSegment>();
        if (session.page > 0) append(session, footer, ChatPanelButton.action(RichText.plain("[上一页]"), selected -> selected.page(selected.page() - 1)));
        footer.add(RichTextSegment.plain(" " + (session.page + 1) + "/" + pages + " "));
        if (session.page + 1 < pages) append(session, footer, ChatPanelButton.action(RichText.plain("[下一页]"), selected -> selected.page(selected.page() + 1)));
        for (ChatPanelButton button : model.footer()) append(session, footer, button);
        if (model.hasHotkeys()) footer.add(RichTextSegment.plain(" F：保存 / 潜行+F：取消"));
        sender.accept(session.player, new RichText(footer));
    }

    private void append(ChatPanelSession session, List<RichTextSegment> line, ChatPanelButton button) {
        if (!allowed(session.player, button.permission())) return;
        TextAction click;
        if (button.kind() == ChatPanelButton.Kind.COPY || button.kind() == ChatPanelButton.Kind.SUGGEST) {
            click = new TextAction(button.kind() == ChatPanelButton.Kind.COPY ? TextAction.Type.COPY_TO_CLIPBOARD : TextAction.Type.SUGGEST_COMMAND, button.value());
        } else {
            String token = UUID.randomUUID().toString();
            session.actions.put(token, button);
            click = new TextAction(TextAction.Type.RUN_COMMAND, "/" + command + " " + session.revision + " " + token);
        }
        line.add(RichTextSegment.plain(" "));
        line.addAll(decorate(button.label(), button.hover(), click).segments());
    }

    private static RichText decorate(RichText label, RichText hover, TextAction click) {
        List<RichTextSegment> segments = new ArrayList<RichTextSegment>();
        for (RichTextSegment text : label.segments()) segments.add(new RichTextSegment(text.text(), text.color(), text.bold(), text.italic(),
                text.underlined(), text.strikethrough(), text.obfuscated(),
                hover == null ? text.hover() : new TextAction(TextAction.Type.HOVER_TEXT, hover.legacyText()), click));
        return new RichText(segments);
    }

    void dispatch(Player player, String revision, String action) {
        requireMain();
        ChatPanelSession session = sessions.get(player.getUniqueId());
        if (session == null || session.player != player || !valid(session) || !session.revision.equals(revision)) return;
        ChatPanelButton button = session.actions.get(action);
        if (button == null || !allowed(player, button.permission())) return;
        invalidate(session); // 一次动作不能用双击或旧聊天重复执行。
        try {
            session.invokingPermission = button.permission();
            button.invoke(session);
            if (valid(session) && session.input == null && session.actions.isEmpty()) render(session, session.model, session.page);
        }
        catch (RuntimeException failure) { close(session); report(session, failure); }
        finally { session.invokingPermission = ""; }
    }

    <T> PromptSession<T> input(ChatPanelSession session, PromptSpec<T> spec, String value, Consumer<T> accepted, Runnable cancelled) {
        requireMain(); Objects.requireNonNull(spec, "spec"); Objects.requireNonNull(accepted, "accepted"); Objects.requireNonNull(cancelled, "cancelled");
        if (!valid(session)) throw new IllegalStateException("chat panel session is no longer active");
        if (session.input != null) session.input.cancel();
        invalidate(session);
        sender.accept(session.player, decorate(RichText.plain("[预填当前值]"), RichText.plain("点击填入聊天输入框，编辑后发送；输入 cancel 取消"),
                new TextAction(TextAction.Type.SUGGEST_COMMAND, value == null ? "" : value)));
        PromptSession<T> prompt = prompts.start(session.player, spec);
        String inputPermission = session.invokingPermission;
        session.input = prompt;
        prompt.completionSync().whenCompleteAsync((outcome, failure) -> {
            if (session.input != prompt) return;
            session.input = null;
            if (!valid(session) || !allowed(session.player, inputPermission)) { close(session); return; }
            try {
                if (failure != null) throw new IllegalStateException("chat panel input failed", failure);
                if (outcome.status() == PromptStatus.ANSWERED) accepted.accept(outcome.value().orElseThrow(() -> new IllegalStateException("missing prompt value")));
                else cancelled.run();
            } catch (RuntimeException error) { close(session); report(session, error); }
        }, owner.syncExecutor());
        return prompt;
    }

    private boolean active(ChatPanelSession session) {
        return !disposed && !owner.isClosed() && !session.closed && sessions.get(session.player.getUniqueId()) == session
                && clock.getAsLong() < session.expires && current(session.player);
    }
    private boolean valid(ChatPanelSession session) { return active(session) && session.model.allowed(session.player); }
    private boolean current(Player player) { return player.isOnline() && plugin.getServer().getPlayer(player.getUniqueId()) == player; }
    private static boolean allowed(Player player, String permission) { return permission.isEmpty() || player.hasPermission(permission); }
    private static void invalidate(ChatPanelSession session) { session.actions.clear(); session.revision = UUID.randomUUID().toString(); }
    void close(ChatPanelSession session) {
        requireMain();
        if (session.closed) return;
        session.closed = true;
        sessions.remove(session.player.getUniqueId(), session);
        invalidate(session);
        if (session.input != null) { PromptSession<?> prompt = session.input; session.input = null; prompt.cancel(); }
    }
    private void prune() {
        for (ChatPanelSession session : new ArrayList<ChatPanelSession>(sessions.values())) if (!valid(session)) close(session);
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        ChatPanelSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) close(session);
    }
    @EventHandler(ignoreCancelled = true) public void swap(PlayerSwapHandItemsEvent event) {
        ChatPanelSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || !valid(session) || !session.model.hasHotkeys()) return;
        event.setCancelled(true);
        invalidate(session);
        try { session.model.hotkey(session, event.getPlayer().isSneaking()); }
        catch (RuntimeException failure) { close(session); report(session, failure); }
    }
    private void report(ChatPanelSession session, RuntimeException failure) {
        logger.error("聊天面板操作失败", failure);
        if (current(session.player)) {
            try { session.model.failure(session, failure); }
            catch (RuntimeException handlerFailure) { logger.error("聊天面板异常处理器失败", handlerFailure); }
        }
    }
    private void ensureOpen() { if (disposed || owner.isClosed()) throw new IllegalStateException("chat panels are closed"); }
    private static void requireMain() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("chat panels require the primary thread"); }
    @Override public void dispose() {
        requireMain();
        if (disposed) return;
        for (ChatPanelSession session : new ArrayList<ChatPanelSession>(sessions.values())) close(session);
        disposed = true;
        HandlerList.unregisterAll(this);
    }
}
