package me.kzheart.klib.ui.chat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import me.kzheart.klib.KLogger;
import me.kzheart.klib.command.Arg;
import me.kzheart.klib.command.Arguments;
import me.kzheart.klib.lang.BukkitMessageRouter;
import me.kzheart.klib.lang.MessageColor;
import me.kzheart.klib.lang.MessageRecipient;
import me.kzheart.klib.lang.RichText;
import me.kzheart.klib.lang.RichTextSegment;
import me.kzheart.klib.lang.TextAction;
import me.kzheart.klib.scheduler.Ticks;
import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.Scope;
import me.kzheart.klib.ui.chat.ChatPanelSession.Page;
import me.kzheart.klib.ui.chat.ChatPanelSession.Pending;
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

/**
 * 作用域持有的聊天面板。按钮命令为 {@code /<command> <页面 id> <按钮 id>}，点击时按实时状态重建页面后路由，
 * 执行后下一 tick 重画当前页；整页作为一条固定行数的消息发送。
 */
public final class BukkitChatPanels implements Listener, Disposable {
    private static final String LOG = "chat-panel";
    private static final int SIGN_LINE_LENGTH = 15;
    private final Scope owner;
    private final Plugin plugin;
    private final String command;
    private final BukkitChatPrompts prompts;
    private final ChatPanelOptions options;
    private final BiConsumer<Player, RichText> sender;
    private final ChatPanelSignInput signs;
    private final KLogger logger;
    private final LongSupplier clock;
    private final Map<UUID, ChatPanelSession> sessions = new HashMap<UUID, ChatPanelSession>();
    private final Set<UUID> signPreferred = new HashSet<UUID>();
    private long sequence;
    private boolean disposed;

    BukkitChatPanels(Scope owner, Plugin plugin, String command, BukkitChatPrompts prompts, ChatPanelOptions options,
                     BiConsumer<Player, RichText> sender, ChatPanelSignInput signs, KLogger logger, LongSupplier clock) {
        this.owner = owner; this.plugin = plugin; this.command = command; this.prompts = prompts;
        this.options = options; this.sender = sender; this.signs = signs; this.logger = logger; this.clock = clock;
    }

    /** 调用方先安装 CommandModule 和 BukkitChatPrompts；必须在插件启动阶段于主线程安装一次。 */
    public static BukkitChatPanels install(Scope owner, Plugin plugin, String command, BukkitChatPrompts prompts) {
        return install(owner, plugin, command, prompts, ChatPanelOptions.defaults());
    }

    public static BukkitChatPanels install(Scope owner, Plugin plugin, String command, BukkitChatPrompts prompts,
                                          ChatPanelOptions options) {
        Objects.requireNonNull(owner, "owner"); Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(prompts, "prompts"); Objects.requireNonNull(command, "command");
        Objects.requireNonNull(options, "options");
        if (!command.matches("[a-z][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("invalid chat panel command label");
        if (!Bukkit.isPrimaryThread() || owner.isClosed()) throw new IllegalStateException("install chat panels at plugin startup on the main thread");
        KLogger logger = owner.findCapability(KLogger.class).orElseGet(() -> new KLogger(plugin.getLogger()));
        BiConsumer<Player, RichText> sender = options.sender();
        if (sender == null) {
            BukkitMessageRouter router = owner.install(new BukkitMessageRouter(plugin.getServer()));
            sender = (player, message) -> router.route(MessageRecipient.of(player, false), message);
        }
        ChatPanelSignInput signs = !options.signInput() ? null
                : options.signs() != null ? options.signs() : PaperSignInput.detect(owner, plugin, logger);
        BukkitChatPanels panels = owner.install(new BukkitChatPanels(owner, plugin, command, prompts, options, sender, signs,
                logger, System::currentTimeMillis));
        Arg<String> page = Arguments.string("page");
        Arg<String> action = Arguments.string("action");
        owner.command(command, root -> root.playerOnly().argument(page).argument(action)
                .executes(context -> panels.dispatch((Player) context.sender(), context.get(page), context.get(action))));
        plugin.getServer().getPluginManager().registerEvents(panels, plugin);
        owner.every(Ticks.seconds(1), panels::prune);
        return panels;
    }

    /**
     * 从外部（命令、菜单）打开页面：复用玩家现有会话并清空返回路径，立即发送。
     * 页面权限或 guard 不满足时给玩家提示并返回空。
     */
    public Optional<ChatPanelSession> open(Player player, ChatPanel panel) {
        requireMain(); Objects.requireNonNull(player, "player"); Objects.requireNonNull(panel, "panel");
        ensureOpen();
        if (!current(player)) throw new IllegalStateException("chat panel target is offline");
        if (!panel.allowed(player)) {
            sender.accept(player, text(ChatPanelText.UNAVAILABLE));
            logger.debug(LOG, "拒绝打开面板 " + panel.id() + "：权限或 guard 不满足，player=" + player.getName());
            return Optional.empty();
        }
        ChatPanelSession session = sessions.get(player.getUniqueId());
        if (session != null && (session.player != player || !active(session))) { close(session, false); session = null; }
        if (session == null) {
            session = new ChatPanelSession(this, player, clock.getAsLong());
            sessions.put(player.getUniqueId(), session);
        }
        cancelInput(session);
        session.history.clear();
        if (session.acting == 0) session.status = null;
        session.lastActive = clock.getAsLong();
        show(session, register(session, panel));
        render(session);
        return Optional.of(session);
    }

    /** 业务结果提示：玩家开着面板时写入状态行并重画，否则直接发送；返回是否进入了面板。 */
    public boolean notice(Player player, RichText message) {
        requireMain(); Objects.requireNonNull(message, "message");
        ChatPanelSession session = sessions.get(player.getUniqueId());
        if (session != null && session.player == player && active(session) && !session.suspended) {
            session.status = message;
            scheduleRedraw(session);
            return true;
        }
        sender.accept(player, message);
        return false;
    }

    public Optional<ChatPanelSession> session(Player player) {
        ChatPanelSession session = sessions.get(player.getUniqueId());
        return session != null && session.player == player && active(session) && !session.suspended ? Optional.of(session) : Optional.<ChatPanelSession>empty();
    }

    void dispatch(Player player, String key, String action) {
        requireMain();
        ChatPanelSession session = sessions.get(player.getUniqueId());
        if (session != null && (session.player != player || !active(session))) { close(session, false); session = null; }
        if (session == null) {
            sender.accept(player, text(ChatPanelText.EXPIRED));
            logger.debug(LOG, "丢弃点击：会话已关闭或过期，player=" + player.getName() + " page=" + key + " action=" + action);
            return;
        }
        session.lastActive = clock.getAsLong();
        if (action.equals("@resume")) {
            Page current = session.pages.get(session.current);
            if (current == null || !current.panel.allowed(player)) { close(session, false); sender.accept(player, text(ChatPanelText.UNAVAILABLE)); return; }
            session.suspended = false; scheduleRedraw(session); return;
        }
        if (action.equals("@close")) { close(session, true); return; }
        session.suspended = false;
        if (action.equals("@cancel")) { cancelByPlayer(session); return; }
        if (action.equals("@mode")) { toggleMode(session); return; }
        if (action.equals("@back")) { session.status = null; if (!back(session)) scheduleRedraw(session); return; }
        Page page = session.pages.get(key);
        if (page == null || page.retired) { stale(session, ChatPanelText.STALE_PAGE, key, action); return; }
        if (!page.panel.allowed(player)) { unavailable(session, page); return; }
        if (action.startsWith("@page.")) { turn(session, page, action); return; }
        ChatPanelView view;
        try { view = build(session, page); }
        catch (RuntimeException failure) {
            logger.error("聊天面板构建失败: page=" + page.key, failure);
            session.status = text(ChatPanelText.FAILED);
            scheduleRedraw(session);
            return;
        }
        ChatPanelView.Route route = view.routes.get(action);
        if (route == null) { stale(session, ChatPanelText.STALE_BUTTON, key, action); return; }
        if (!allowed(player, route.button.permission())) {
            logger.debug(LOG, "丢弃点击：缺少按钮权限 " + route.button.permission() + "，player=" + player.getName());
            session.status = text(ChatPanelText.NO_PERMISSION);
            scheduleRedraw(session);
            return;
        }
        cancelInput(session);
        session.suspended = false;
        session.status = null;
        if (!page.key.equals(session.current)) show(session, page);
        if (page.panel.once()) page.retired = true;
        int navigation = session.navigation;
        int renders = session.renders;
        session.invokingPermission = route.button.permission();
        session.acting++;
        try {
            if (route.reset) route.button.resetAction().accept(session);
            else route.button.invoke(session);
        } catch (RuntimeException failure) {
            fail(session, page, failure);
        } finally {
            session.acting--;
            session.invokingPermission = "";
        }
        if (session.closed) return;
        if (page.retired && session.navigation == navigation && session.pending == null && !back(session)) {
            close(session, true);
            return;
        }
        redrawUnlessRendered(session, renders);
    }

    void navigate(ChatPanelSession session, ChatPanel panel) {
        requireMain(); requireActive(session);
        if (!panel.allowed(session.player)) {
            logger.debug(LOG, "拒绝进入面板 " + panel.id() + "：权限或 guard 不满足，player=" + session.player.getName());
            session.status = text(ChatPanelText.UNAVAILABLE);
            scheduleRedraw(session);
            return;
        }
        cancelInput(session);
        Page current = session.pages.get(session.current);
        if (current != null && !current.retired && !current.panel.once()) {
            session.history.remove(current.key);
            session.history.push(current.key);
        }
        show(session, register(session, panel));
        scheduleRedraw(session);
    }

    boolean back(ChatPanelSession session) {
        requireMain();
        while (!session.history.isEmpty()) {
            Page page = session.pages.get(session.history.pop());
            if (page == null || page.retired || page.key.equals(session.current)) continue;
            cancelInput(session);
            show(session, page);
            scheduleRedraw(session);
            return true;
        }
        return false;
    }

    void page(ChatPanelSession session, int index) {
        requireMain();
        Page page = session.pages.get(session.current);
        if (page == null) return;
        page.index = Math.max(0, index);
        scheduleRedraw(session);
    }

    /** 动作里已经通过 open(...) 立即发送过整页时不再重复发送；之后设置的状态行仍会安排重画。 */
    private void redrawUnlessRendered(ChatPanelSession session, int renders) {
        if (session.renders == renders) scheduleRedraw(session);
    }

    void scheduleRedraw(ChatPanelSession session) {
        requireMain();
        if (session.closed || session.suspended || session.rendering || session.redraw != null || disposed || owner.isClosed()) return;
        session.redraw = owner.after(Ticks.of(1), () -> { session.redraw = null; render(session); });
    }

    <T> void input(ChatPanelSession session, ChatPanelInput<T> input, Consumer<T> accepted, Runnable cancelled) {
        requireMain(); Objects.requireNonNull(input, "input"); Objects.requireNonNull(accepted, "accepted");
        requireActive(session);
        cancelInput(session);
        Pending<T> pending = new Pending<T>(input, accepted, cancelled, session.current, session.invokingPermission);
        session.pending = pending;
        session.status = null;
        session.lastActive = clock.getAsLong();
        boolean sign = signs != null && input.signAllowed() && signPreferred.contains(session.player.getUniqueId());
        if (sign && input.current().length() > SIGN_LINE_LENGTH * 3) {
            sign = false;
            session.status = text(ChatPanelText.INPUT_SIGN_TOO_LONG);
        }
        if (sign && signs.open(session.player, signLines(input.current()), values -> signed(session, pending, values))) {
            pending.sign = true;
            if (session.pending == pending) pending.timeout = owner.after(input.timeout(), () -> {
                if (session.pending != pending) return;
                session.pending = null;
                signs.cancel(session.player);
                complete(session, pending, PromptStatus.TIMED_OUT, null);
            });
        } else {
            PromptSpec<T> spec = PromptSpec.builder(input.parser()).timeout(input.timeout()).cancelKeyword(input.cancelKeyword())
                    .invalidMessage(text(ChatPanelText.INPUT_INVALID).legacyText()).cancelledMessage("").timeoutMessage("").build();
            PromptSession<T> prompt = prompts.start(session.player, spec);
            pending.prompt = prompt;
            prompt.completionSync().whenCompleteAsync((outcome, failure) -> {
                if (session.pending != pending) return;
                session.pending = null;
                if (failure != null) {
                    logger.error("聊天面板输入失败", failure);
                    if (active(session)) { session.status = text(ChatPanelText.FAILED); scheduleRedraw(session); }
                    return;
                }
                complete(session, pending, outcome.status(), outcome.value().orElse(null));
            }, owner.syncExecutor());
        }
        scheduleRedraw(session);
    }

    private <T> void signed(ChatPanelSession session, Pending<T> pending, String[] values) {
        if (session.pending != pending) return;
        session.pending = null;
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < Math.min(3, values.length); i++) joined.append(values[i]);
        Optional<T> parsed;
        try { parsed = pending.input.parser().parse(joined.toString().trim()); }
        catch (RuntimeException invalid) { parsed = Optional.empty(); }
        if (parsed == null || !parsed.isPresent()) {
            if (pending.timeout != null) pending.timeout.cancel();
            if (active(session)) { session.status = text(ChatPanelText.INPUT_SIGN_INVALID); scheduleRedraw(session); }
            return;
        }
        complete(session, pending, PromptStatus.ANSWERED, parsed.get());
    }

    /** 调用前已把 pending 从会话移除。 */
    private <T> void complete(ChatPanelSession session, Pending<T> pending, PromptStatus status, T value) {
        if (pending.timeout != null) pending.timeout.cancel();
        if (!active(session)) {
            logger.debug(LOG, "丢弃输入结果：会话已关闭，player=" + session.player.getName());
            return;
        }
        session.lastActive = clock.getAsLong();
        Page origin = session.pages.get(pending.origin);
        if (origin == null || !origin.panel.allowed(session.player)) {
            logger.debug(LOG, "丢弃输入结果：面板目标已失效，player=" + session.player.getName());
            sender.accept(session.player, text(ChatPanelText.UNAVAILABLE));
            close(session, false);
            return;
        }
        if (!allowed(session.player, pending.permission)) {
            session.status = text(ChatPanelText.NO_PERMISSION);
            scheduleRedraw(session);
            return;
        }
        session.status = null;
        int renders = session.renders;
        session.invokingPermission = pending.permission;
        session.acting++;
        try {
            if (status == PromptStatus.ANSWERED) pending.accepted.accept(value);
            else {
                session.status = text(status == PromptStatus.TIMED_OUT ? ChatPanelText.INPUT_TIMEOUT : ChatPanelText.INPUT_CANCELLED);
                if (pending.cancelled != null) pending.cancelled.run();
            }
        } catch (RuntimeException failure) {
            fail(session, origin, failure);
        } finally {
            session.acting--;
            session.invokingPermission = "";
        }
        if (!session.closed) redrawUnlessRendered(session, renders);
    }

    private void cancelByPlayer(ChatPanelSession session) {
        Pending<?> pending = session.pending;
        if (pending == null) { scheduleRedraw(session); return; }
        cancelInput(session);
        complete(session, pending, PromptStatus.CANCELLED, null);
    }

    /** 静默放弃进行中的输入（被新的操作替代）。 */
    private void cancelInput(ChatPanelSession session) {
        Pending<?> pending = session.pending;
        if (pending == null) return;
        session.pending = null;
        if (pending.timeout != null) pending.timeout.cancel();
        if (pending.prompt != null) pending.prompt.cancel();
        if (pending.sign && signs != null) signs.cancel(session.player);
    }

    private void toggleMode(ChatPanelSession session) {
        UUID id = session.player.getUniqueId();
        if (signs == null) signPreferred.remove(id);
        else if (!signPreferred.remove(id)) signPreferred.add(id);
        session.status = text(signPreferred.contains(id) ? ChatPanelText.MODE_SIGN : ChatPanelText.MODE_CHAT);
        scheduleRedraw(session);
    }

    private void turn(ChatPanelSession session, Page page, String action) {
        int index;
        try { index = Integer.parseInt(action.substring("@page.".length())); }
        catch (NumberFormatException invalid) { stale(session, ChatPanelText.STALE_BUTTON, page.key, action); return; }
        if (!page.key.equals(session.current)) show(session, page);
        page.index = Math.max(0, index);
        session.status = null;
        scheduleRedraw(session);
    }

    private void stale(ChatPanelSession session, ChatPanelText message, String key, String action) {
        logger.debug(LOG, "旧按钮已刷新：player=" + session.player.getName() + " page=" + key + " action=" + action);
        session.status = text(message);
        scheduleRedraw(session);
    }

    private void unavailable(ChatPanelSession session, Page page) {
        logger.debug(LOG, "丢弃点击：面板 " + page.key + " 权限或 guard 不满足，player=" + session.player.getName());
        session.pages.remove(page.key);
        sender.accept(session.player, text(ChatPanelText.UNAVAILABLE));
        if (page.key.equals(session.current)) close(session, false);
        else scheduleRedraw(session);
    }

    private void fail(ChatPanelSession session, Page page, RuntimeException failure) {
        logger.error("聊天面板操作失败: page=" + page.key, failure);
        if (page.panel.errorHandler() == null) { session.status = text(ChatPanelText.FAILED); return; }
        try { page.panel.errorHandler().accept(session, failure); }
        catch (RuntimeException handlerFailure) { logger.error("聊天面板异常处理器失败", handlerFailure); }
    }

    private Page register(ChatPanelSession session, ChatPanel panel) {
        String key = panel.once() ? panel.id() + "~" + Long.toString(++sequence, 36) : panel.id();
        Page page = session.pages.get(key);
        if (page == null) { page = new Page(key, panel); session.pages.put(key, page); }
        else page.panel = panel;
        return page;
    }

    private static void show(ChatPanelSession session, Page page) {
        session.suspended = false;
        session.current = page.key;
        session.navigation++;
    }

    private ChatPanelView build(ChatPanelSession session, Page page) {
        ChatPanelView view = new ChatPanelView(session, page.key, "/" + command, options, page.panel.perLine());
        page.panel.content().accept(view);
        return view;
    }

    void render(ChatPanelSession session) {
        if (session.redraw != null) { session.redraw.cancel(); session.redraw = null; }
        if (!active(session)) { if (sessions.get(session.player.getUniqueId()) == session) close(session, false); return; }
        if (session.suspended) return;
        Page page = session.pages.get(session.current);
        if (page == null || !page.panel.allowed(session.player)) {
            logger.debug(LOG, "关闭面板：当前页面已失效，player=" + session.player.getName());
            close(session, false);
            sender.accept(session.player, text(ChatPanelText.UNAVAILABLE));
            return;
        }
        ChatPanelView view;
        session.rendering = true;
        try { view = build(session, page); }
        catch (RuntimeException failure) {
            session.rendering = false;
            logger.error("聊天面板构建失败: page=" + page.key, failure);
            close(session, false);
            sender.accept(session.player, text(ChatPanelText.FAILED));
            return;
        }
        session.renders++;
        try { sender.accept(session.player, compose(session, page, view)); }
        finally { session.rendering = false; }
    }

    void output(ChatPanelSession session, Runnable action) {
        requireMain(); requireActive(session); cancelInput(session);
        if (session.redraw != null) { session.redraw.cancel(); session.redraw = null; }
        session.suspended = true;
        final long generation = ++session.outputGeneration;
        RichText[] blank = new RichText[options.lines()]; Arrays.fill(blank, RichText.plain(""));
        sender.accept(session.player, join(Arrays.asList(blank)));
        try { action.run(); }
        catch (RuntimeException failure) { session.suspended = false; throw failure; }
        // Klib 命令会通过所属 Scope 派发到主线程；在它的下一 tick 输出/清屏之后再附加返回。
        owner.after(Ticks.of(1), () -> {
            if (!active(session) || !session.suspended || generation != session.outputGeneration) return;
            ChatPanelView view = build(session, session.pages.get(session.current));
            List<RichTextSegment> footer = new ArrayList<RichTextSegment>();
            append(footer, control(text(ChatPanelText.RESUME), null, view.run("@resume")));
            append(footer, control(text(ChatPanelText.CLOSE), null, view.run("@close")));
            sender.accept(session.player, ChatPanelView.indent(footer));
        });
    }

    private RichText compose(ChatPanelSession session, Page page, ChatPanelView view) {
        int lines = options.lines();
        List<RichTextSegment> header = new ArrayList<RichTextSegment>(page.panel.title().segments());
        if (view.subtitle() != null) {
            header.add(new RichTextSegment(" › ", MessageColor.DARK_GRAY, true, null, null));
            header.addAll(view.subtitle().segments());
        }
        List<RichText> title = ChatPanelLayout.limited(ChatPanelView.indent(header), options.width(), lines <= 10 ? 1 : 2);
        RichText statusText = statusLine(session, view);
        List<RichText> status = statusText.plainText().trim().isEmpty() ? new ArrayList<RichText>() : ChatPanelLayout.limited(statusText, options.width(), lines <= 10 ? 1 : 3);
        // 先为最宽的分页导航预留容量，避免添加分页按钮后超屏。
        List<RichText> navigation = ChatPanelLayout.wrap(footer(session, page, view, 2), options.width());
        int capacity = Math.max(1, lines - title.size() - status.size() - navigation.size() - 3);
        List<List<RichText>> pages = paginate(view.blocks(), capacity);
        int count = Math.max(1, pages.size());
        page.index = Math.max(0, Math.min(page.index, count - 1));
        List<RichText> out = new ArrayList<RichText>(title);
        out.add(RichText.plain(""));
        if (!pages.isEmpty()) out.addAll(pages.get(page.index));
        out.add(RichText.plain(""));
        out.addAll(status);
        out.addAll(ChatPanelLayout.wrap(footer(session, page, view, count), options.width()));
        // 控件跟随正文，留白放到末尾；收起聊天框时仍能看到展开提示。
        if (out.size() < lines - 6) {
            while (out.size() < lines - 7) out.add(RichText.plain(""));
            out.add(ChatPanelView.indent(text(ChatPanelText.EXPAND).segments()));
        }
        while (out.size() < lines) out.add(RichText.plain(""));
        return join(out);
    }

    private static RichText join(List<RichText> lines) {
        List<RichTextSegment> joined = new ArrayList<RichTextSegment>();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) joined.add(RichTextSegment.plain("\n"));
            joined.addAll(lines.get(i).segments());
        }
        return new RichText(joined);
    }

    static List<List<RichText>> paginate(List<List<RichText>> blocks, int capacity) {
        List<List<RichText>> pages = new ArrayList<List<RichText>>();
        List<RichText> current = new ArrayList<RichText>();
        for (List<RichText> block : blocks) {
            if (block.isEmpty()) continue;
            int needed = block.size() + (current.isEmpty() ? 0 : 1);
            if (!current.isEmpty() && current.size() + needed > capacity) { pages.add(current); current = new ArrayList<RichText>(); }
            if (!current.isEmpty()) current.add(RichText.plain(""));
            for (RichText line : block) {
                if (current.size() >= capacity) { pages.add(current); current = new ArrayList<RichText>(); }
                current.add(line);
            }
        }
        if (!current.isEmpty()) pages.add(current);
        return pages;
    }

    private RichText statusLine(ChatPanelSession session, ChatPanelView view) {
        Pending<?> pending = session.pending;
        if (pending == null) return session.status == null ? RichText.plain("") : ChatPanelView.indent(session.status.segments());
        List<RichTextSegment> line = new ArrayList<RichTextSegment>();
        if (pending.input.hint() != null) { line.addAll(pending.input.hint().segments()); line.add(RichTextSegment.plain(" ")); }
        if (pending.sign) line.addAll(text(ChatPanelText.INPUT_SIGN_HINT).segments());
        else {
            line.addAll(text(ChatPanelText.INPUT_CHAT_HINT).segments());
            line.add(RichTextSegment.plain(" "));
            if (!pending.input.current().isEmpty()) line.addAll(control(text(ChatPanelText.PREFILL), text(ChatPanelText.PREFILL_HOVER),
                    new TextAction(TextAction.Type.SUGGEST_COMMAND, pending.input.current())));
        }
        line.add(RichTextSegment.plain(" "));
        line.addAll(control(text(ChatPanelText.CANCEL_INPUT), null, view.run("@cancel")));
        return ChatPanelView.indent(line);
    }

    private RichText footer(ChatPanelSession session, Page page, ChatPanelView view, int count) {
        List<RichTextSegment> line = new ArrayList<RichTextSegment>();
        if (count > 1) {
            line.addAll(control(text(ChatPanelText.PREVIOUS), null, page.index > 0 ? view.run("@page." + (page.index - 1)) : null));
            line.add(new RichTextSegment(" " + (page.index + 1) + " / " + count + " ", MessageColor.WHITE, false, null, null));
            line.addAll(control(text(ChatPanelText.NEXT), null, page.index + 1 < count ? view.run("@page." + (page.index + 1)) : null));
        }
        for (ChatPanelButton button : view.footer) append(line, view.render(button));
        if (!session.history.isEmpty()) append(line, control(text(ChatPanelText.BACK), null, view.run("@back")));
        if (signs != null) {
            boolean sign = signPreferred.contains(session.player.getUniqueId());
            append(line, control(text(sign ? ChatPanelText.MODE_SIGN : ChatPanelText.MODE_CHAT), text(ChatPanelText.MODE_HOVER), view.run("@mode")));
        }
        append(line, control(text(ChatPanelText.CLOSE), null, view.run("@close")));
        return ChatPanelView.indent(line);
    }

    private static void append(List<RichTextSegment> line, List<RichTextSegment> button) {
        if (!line.isEmpty()) line.add(RichTextSegment.plain(" "));
        line.addAll(button);
    }

    /** 内置控件；click 为空时显示为不可点的暗色。 */
    private static List<RichTextSegment> control(RichText label, RichText hover, TextAction click) {
        TextAction tip = ChatPanelView.hover(hover);
        List<RichTextSegment> out = new ArrayList<RichTextSegment>();
        out.add(new RichTextSegment("[", MessageColor.DARK_GRAY, false, tip, click));
        if (click == null) out.add(new RichTextSegment(label.plainText(), MessageColor.DARK_GRAY, false, null, null));
        else out.addAll(ChatPanelView.decorate(label.segments(), null, tip, click));
        out.add(new RichTextSegment("]", MessageColor.DARK_GRAY, false, tip, click));
        return out;
    }

    private String[] signLines(String current) {
        String[] lines = new String[4];
        for (int i = 0; i < 3; i++) {
            int from = Math.min(current.length(), i * SIGN_LINE_LENGTH);
            lines[i] = current.substring(from, Math.min(current.length(), from + SIGN_LINE_LENGTH));
        }
        lines[3] = text(ChatPanelText.SIGN_LINE).plainText();
        return lines;
    }

    private RichText text(ChatPanelText key) { return options.text(key); }

    private boolean active(ChatPanelSession session) {
        return !disposed && !owner.isClosed() && !session.closed && sessions.get(session.player.getUniqueId()) == session
                && clock.getAsLong() - session.lastActive < options.idleMillis() && current(session.player);
    }
    private void requireActive(ChatPanelSession session) {
        if (!active(session)) throw new IllegalStateException("chat panel session is no longer active");
    }
    private boolean current(Player player) { return player.isOnline() && plugin.getServer().getPlayer(player.getUniqueId()) == player; }
    private static boolean allowed(Player player, String permission) { return permission.isEmpty() || player.hasPermission(permission); }

    void close(ChatPanelSession session, boolean clear) {
        requireMain();
        if (session.closed) return;
        session.closed = true;
        sessions.remove(session.player.getUniqueId(), session);
        if (session.redraw != null) { session.redraw.cancel(); session.redraw = null; }
        cancelInput(session);
        if (clear && current(session.player)) {
            RichText[] blank = new RichText[options.lines()];
            Arrays.fill(blank, RichText.plain(""));
            blank[blank.length - 1] = ChatPanelView.indent(text(ChatPanelText.CLOSED).segments());
            sender.accept(session.player, join(Arrays.asList(blank)));
        }
    }

    private void prune() {
        for (ChatPanelSession session : new ArrayList<ChatPanelSession>(sessions.values())) if (!active(session)) close(session, false);
    }

    @EventHandler public void quit(PlayerQuitEvent event) {
        ChatPanelSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) close(session, false);
    }

    @EventHandler(ignoreCancelled = true) public void swap(PlayerSwapHandItemsEvent event) {
        ChatPanelSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || session.player != event.getPlayer() || !active(session)) return;
        Page page = session.pages.get(session.current);
        if (page == null || !page.panel.hasHotkeys() || !page.panel.allowed(session.player)) return;
        event.setCancelled(true);
        session.lastActive = clock.getAsLong();
        session.status = null;
        int renders = session.renders;
        session.acting++;
        try { page.panel.hotkey(session, event.getPlayer().isSneaking()); }
        catch (RuntimeException failure) { fail(session, page, failure); }
        finally { session.acting--; }
        if (!session.closed) redrawUnlessRendered(session, renders);
    }

    private void ensureOpen() { if (disposed || owner.isClosed()) throw new IllegalStateException("chat panels are closed"); }
    private static void requireMain() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("chat panels require the primary thread"); }

    @Override public void dispose() {
        requireMain();
        if (disposed) return;
        for (ChatPanelSession session : new ArrayList<ChatPanelSession>(sessions.values())) close(session, false);
        disposed = true;
        signPreferred.clear();
        HandlerList.unregisterAll(this);
    }
}
