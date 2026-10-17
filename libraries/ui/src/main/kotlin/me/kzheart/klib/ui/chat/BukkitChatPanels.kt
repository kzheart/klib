package me.kzheart.klib.ui.chat

import java.util.Optional
import java.util.UUID
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.LongSupplier
import me.kzheart.klib.KLogger
import me.kzheart.klib.command.Arguments
import me.kzheart.klib.lang.BukkitMessageRouter
import me.kzheart.klib.lang.MessageColor
import me.kzheart.klib.lang.MessageRecipient
import me.kzheart.klib.lang.RichText
import me.kzheart.klib.lang.RichTextSegment
import me.kzheart.klib.lang.TextAction
import me.kzheart.klib.scheduler.AsyncTasks
import me.kzheart.klib.scheduler.Ticks
import me.kzheart.klib.scope.Disposable
import me.kzheart.klib.scope.Scope
import me.kzheart.klib.ui.chat.ChatPanelSession.Page
import me.kzheart.klib.ui.chat.ChatPanelSession.Pending
import me.kzheart.klib.ui.prompt.BukkitChatPrompts
import me.kzheart.klib.ui.prompt.PromptSpec
import me.kzheart.klib.ui.prompt.PromptStatus
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.plugin.Plugin

/** 统一管理聊天页面寻址、实时权限检查、一次性动作和输入生命周期。 */
class BukkitChatPanels internal constructor(private val owner: Scope, private val plugin: Plugin,
    private val command: String, private val prompts: BukkitChatPrompts, private val options: ChatPanelOptions,
    private val sender: BiConsumer<Player, RichText>, private val signs: ChatPanelSignInput?,
    private val logger: KLogger, private val clock: LongSupplier) : Listener, Disposable {
    private val sessions = HashMap<UUID, ChatPanelSession>()
    private val inputModes = HashMap<UUID, Boolean>()
    private var sequence = 0L
    private var disposed = false

    fun open(player: Player, panel: ChatPanel): Optional<ChatPanelSession> {
        requireMain(); ensureOpen()
        check(current(player)) { "chat panel target is offline" }
        if (!panel.allowed(player)) {
            sender.accept(player, text(ChatPanelText.UNAVAILABLE))
            logger.debug(LOG, "拒绝打开面板 ${panel.id()}：权限或 guard 不满足，player=${player.name}")
            return Optional.empty()
        }
        val session = liveSession(player) ?: ChatPanelSession(this, player, clock.asLong).also { sessions[player.uniqueId] = it }
        cancelInput(session); session.history.clear()
        if (session.acting == 0) session.status = null
        session.lastActive = clock.asLong
        show(session, register(session, panel)); render(session)
        return Optional.of(session)
    }
    private fun liveSession(player: Player): ChatPanelSession? {
        val session = sessions[player.uniqueId] ?: return null
        if (session.player !== player || !active(session)) { close(session, false); return null }
        return session
    }
    fun notice(player: Player, message: RichText): Boolean {
        requireMain()
        val session = sessions[player.uniqueId]
        if (session != null && session.player === player && active(session) && !session.suspended) {
            session.status = message; scheduleRedraw(session); return true
        }
        sender.accept(player, message)
        return false
    }
    fun session(player: Player): Optional<ChatPanelSession> = Optional.ofNullable(sessions[player.uniqueId]?.takeIf {
        it.player === player && active(it) && !it.suspended
    })

    @JvmName("dispatch") internal fun dispatch(player: Player, key: String, action: String) {
        requireMain()
        val session = liveSession(player)
        if (session == null) {
            sender.accept(player, text(ChatPanelText.EXPIRED))
            logger.debug(LOG, "丢弃点击：会话已关闭或过期，player=${player.name} page=$key action=$action")
            return
        }
        session.lastActive = clock.asLong
        if (action == "@resume") {
            val page = session.pages[session.current]
            if (page == null || !page.panel.allowed(player)) { close(session, false); sender.accept(player, text(ChatPanelText.UNAVAILABLE)); return }
            session.suspended = false; scheduleRedraw(session); return
        }
        if (action == "@close") { close(session, true); return }
        session.suspended = false
        when (action) {
            "@cancel" -> { cancelByPlayer(session); return }
            "@mode" -> { toggleMode(session); return }
            "@back" -> { session.status = null; if (!back(session)) scheduleRedraw(session); return }
        }
        val page = session.pages[key]
        if (page == null || page.retired) { stale(session, ChatPanelText.STALE_PAGE, key, action); return }
        if (!page.panel.allowed(player)) { unavailable(session, page); return }
        if (action.startsWith("@page.")) { turn(session, page, action); return }
        val view = try { build(session, page) } catch (failure: RuntimeException) {
            logger.error("聊天面板构建失败: page=${page.key}", failure)
            session.status = text(ChatPanelText.FAILED); scheduleRedraw(session); return
        }
        val route = view.routes[action]
        if (route == null) { stale(session, ChatPanelText.STALE_BUTTON, key, action); return }
        if (!allowed(player, route.button.permission())) {
            logger.debug(LOG, "丢弃点击：缺少按钮权限 ${route.button.permission()}，player=${player.name}")
            session.status = text(ChatPanelText.NO_PERMISSION); scheduleRedraw(session); return
        }
        cancelInput(session); session.suspended = false; session.status = null
        if (page.key != session.current) show(session, page)
        if (page.panel.once()) page.retired = true
        val navigation = session.navigation
        val renders = session.renders
        session.invokingPermission = route.button.permission(); session.acting++
        try { if (route.reset) route.button.resetAction()!!.accept(session) else route.button.invoke(session) }
        catch (failure: RuntimeException) { fail(session, page, failure) }
        finally { session.acting--; session.invokingPermission = "" }
        if (session.closed) return
        if (page.retired && session.navigation == navigation && session.pending == null && !back(session)) { close(session, true); return }
        redrawUnlessRendered(session, renders)
    }
    @JvmName("navigate") internal fun navigate(session: ChatPanelSession, panel: ChatPanel) {
        requireMain(); requireActive(session)
        if (!panel.allowed(session.player)) {
            logger.debug(LOG, "拒绝进入面板 ${panel.id()}：权限或 guard 不满足，player=${session.player.name}")
            session.status = text(ChatPanelText.UNAVAILABLE); scheduleRedraw(session); return
        }
        cancelInput(session)
        session.pages[session.current]?.takeIf { !it.retired && !it.panel.once() }?.let {
            session.history.remove(it.key); session.history.push(it.key)
        }
        show(session, register(session, panel)); scheduleRedraw(session)
    }
    @JvmName("back") internal fun back(session: ChatPanelSession): Boolean {
        requireMain()
        while (session.history.isNotEmpty()) {
            val page = session.pages[session.history.pop()] ?: continue
            if (page.retired || page.key == session.current) continue
            cancelInput(session); show(session, page); scheduleRedraw(session); return true
        }
        return false
    }
    @JvmName("page") internal fun page(session: ChatPanelSession, index: Int) {
        requireMain()
        val page = session.pages[session.current] ?: return
        page.index = maxOf(0, index); scheduleRedraw(session)
    }
    private fun redrawUnlessRendered(session: ChatPanelSession, renders: Int) { if (session.renders == renders) scheduleRedraw(session) }
    @JvmName("scheduleRedraw") internal fun scheduleRedraw(session: ChatPanelSession) {
        requireMain()
        if (session.closed || session.suspended || session.rendering || session.redraw != null || disposed || owner.isClosed) return
        session.redraw = owner.after(Ticks.of(1)) { session.redraw = null; render(session) }
    }

    @JvmName("input") internal fun <T> input(session: ChatPanelSession, input: ChatPanelInput<T>, accepted: Consumer<T>, cancelled: Runnable?) {
        requireMain(); requireActive(session); cancelInput(session)
        val pending = Pending(input, accepted, cancelled, session.current!!, session.invokingPermission)
        session.pending = pending; session.status = null; session.lastActive = clock.asLong
        var sign = signs != null && input.signAllowed() && prefersSign(session.player.uniqueId)
        if (sign && !fitsSign(input)) { sign = false; session.status = text(ChatPanelText.INPUT_SIGN_TOO_LONG) }
        if (sign && signs!!.open(session.player, signLines(input.current())) { signed(session, pending, it) }) {
            pending.sign = true
            if (session.pending === pending) pending.timeout = owner.after(input.timeout()) {
                if (session.pending === pending) {
                    session.pending = null; signs.cancel(session.player)
                    complete(session, pending, PromptStatus.TIMED_OUT, null)
                }
            }
        } else {
            val spec = PromptSpec.builder(input.parser()).timeout(input.timeout()).cancelKeyword(input.cancelKeyword())
                .invalidMessage(text(ChatPanelText.INPUT_INVALID).legacyText()).cancelledMessage("").timeoutMessage("").build()
            val prompt = prompts.start(session.player, spec)
            pending.prompt = prompt
            AsyncTasks.thenSync(prompt.completionSync(), owner, { outcome ->
                if (session.pending === pending) {
                    session.pending = null; complete(session, pending, outcome.status(), outcome.value().orElse(null))
                }
            }, { failure ->
                if (session.pending === pending) {
                    session.pending = null; logger.error("聊天面板输入失败", failure)
                    if (active(session)) { session.status = text(ChatPanelText.FAILED); scheduleRedraw(session) }
                }
            })
        }
        scheduleRedraw(session)
    }
    private fun <T> signed(session: ChatPanelSession, pending: Pending<T>, values: Array<String>) {
        if (session.pending !== pending) return
        session.pending = null
        val joined = values.take(3).joinToString("").trim()
        if (joined.equals(pending.input.cancelKeyword(), true)) { complete(session, pending, PromptStatus.CANCELLED, null); return }
        val parsed = try { pending.input.parser().parse(joined) } catch (_: RuntimeException) { null }
        if (parsed == null || !parsed.isPresent) {
            pending.timeout?.cancel()
            if (active(session)) { session.status = text(ChatPanelText.INPUT_SIGN_INVALID); scheduleRedraw(session) }
            return
        }
        complete(session, pending, PromptStatus.ANSWERED, parsed.get())
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> complete(session: ChatPanelSession, pending: Pending<T>, status: PromptStatus, value: T?) {
        pending.timeout?.cancel()
        if (!active(session)) { logger.debug(LOG, "丢弃输入结果：会话已关闭，player=${session.player.name}"); return }
        session.lastActive = clock.asLong
        val origin = session.pages[pending.origin]
        if (origin == null || !origin.panel.allowed(session.player)) {
            logger.debug(LOG, "丢弃输入结果：面板目标已失效，player=${session.player.name}")
            sender.accept(session.player, text(ChatPanelText.UNAVAILABLE)); close(session, false); return
        }
        if (!allowed(session.player, pending.permission)) { session.status = text(ChatPanelText.NO_PERMISSION); scheduleRedraw(session); return }
        session.status = null
        val renders = session.renders
        session.invokingPermission = pending.permission; session.acting++
        try {
            if (status == PromptStatus.ANSWERED) pending.accepted.accept(value as T)
            else {
                session.status = text(if (status == PromptStatus.TIMED_OUT) ChatPanelText.INPUT_TIMEOUT else ChatPanelText.INPUT_CANCELLED)
                pending.cancelled?.run()
            }
        } catch (failure: RuntimeException) { fail(session, origin, failure) }
        finally { session.acting--; session.invokingPermission = "" }
        if (!session.closed) redrawUnlessRendered(session, renders)
    }
    private fun cancelByPlayer(session: ChatPanelSession) {
        val pending = session.pending ?: run { scheduleRedraw(session); return }
        cancelInput(session); complete(session, pending, PromptStatus.CANCELLED, null)
    }
    private fun cancelInput(session: ChatPanelSession) {
        val pending = session.pending ?: return
        session.pending = null
        pending.timeout?.cancel(); pending.prompt?.cancel()
        if (pending.sign) signs?.cancel(session.player)
    }
    private fun prefersSign(id: UUID): Boolean = signs != null && (inputModes[id] ?: options.preferSignInput())
    private fun toggleMode(session: ChatPanelSession) {
        val pending = session.pending
        if (pending != null && (!pending.input.signAllowed() || !fitsSign(pending.input))) {
            session.status = text(ChatPanelText.INPUT_CHAT_ONLY); scheduleRedraw(session); return
        }
        val sign = signs != null && !prefersSign(session.player.uniqueId)
        inputModes[session.player.uniqueId] = sign
        if (pending != null) switchInput(session, pending)
        else { session.status = text(if (sign) ChatPanelText.MODE_SIGN else ChatPanelText.MODE_CHAT); scheduleRedraw(session) }
    }
    private fun <T> switchInput(session: ChatPanelSession, pending: Pending<T>) {
        val origin = session.pages[pending.origin]
        if (origin == null || !origin.panel.allowed(session.player)) { close(session, false); sender.accept(session.player, text(ChatPanelText.UNAVAILABLE)); return }
        if (!allowed(session.player, pending.permission)) { cancelInput(session); session.status = text(ChatPanelText.NO_PERMISSION); scheduleRedraw(session); return }
        val permission = session.invokingPermission
        session.invokingPermission = pending.permission
        try { input(session, pending.input, pending.accepted, pending.cancelled) } finally { session.invokingPermission = permission }
    }
    private fun turn(session: ChatPanelSession, page: Page, action: String) {
        val index = action.substring("@page.".length).toIntOrNull()
        if (index == null) { stale(session, ChatPanelText.STALE_BUTTON, page.key, action); return }
        if (page.key != session.current) show(session, page)
        page.index = maxOf(0, index); session.status = null; scheduleRedraw(session)
    }
    private fun stale(session: ChatPanelSession, message: ChatPanelText, key: String, action: String) {
        logger.debug(LOG, "旧按钮已刷新：player=${session.player.name} page=$key action=$action")
        session.status = text(message); scheduleRedraw(session)
    }
    private fun unavailable(session: ChatPanelSession, page: Page) {
        logger.debug(LOG, "丢弃点击：面板 ${page.key} 权限或 guard 不满足，player=${session.player.name}")
        session.pages.remove(page.key); sender.accept(session.player, text(ChatPanelText.UNAVAILABLE))
        if (page.key == session.current) close(session, false) else scheduleRedraw(session)
    }
    private fun fail(session: ChatPanelSession, page: Page, failure: RuntimeException) {
        logger.error("聊天面板操作失败: page=${page.key}", failure)
        val handler = page.panel.errorHandler()
        if (handler == null) { session.status = text(ChatPanelText.FAILED); return }
        try { handler.accept(session, failure) } catch (handlerFailure: RuntimeException) { logger.error("聊天面板异常处理器失败", handlerFailure) }
    }
    private fun register(session: ChatPanelSession, panel: ChatPanel): Page {
        val key = if (panel.once()) panel.id() + "~" + java.lang.Long.toString(++sequence, 36) else panel.id()
        return session.pages.getOrPut(key) { Page(key, panel) }.also { it.panel = panel }
    }
    private fun build(session: ChatPanelSession, page: Page): ChatPanelView =
        ChatPanelView(session, page.key, "/$command", options, page.panel.perLine()).also { page.panel.content().accept(it) }
    @JvmName("render") internal fun render(session: ChatPanelSession) {
        session.redraw?.cancel(); session.redraw = null
        if (!active(session)) { if (sessions[session.player.uniqueId] === session) close(session, false); return }
        if (session.suspended) return
        val page = session.pages[session.current]
        if (page == null || !page.panel.allowed(session.player)) {
            logger.debug(LOG, "关闭面板：当前页面已失效，player=${session.player.name}")
            close(session, false); sender.accept(session.player, text(ChatPanelText.UNAVAILABLE)); return
        }
        session.rendering = true
        val view = try { build(session, page) } catch (failure: RuntimeException) {
            session.rendering = false; logger.error("聊天面板构建失败: page=${page.key}", failure)
            close(session, false); sender.accept(session.player, text(ChatPanelText.FAILED)); return
        }
        session.renders++
        try { sender.accept(session.player, compose(session, page, view)) } finally { session.rendering = false }
    }
    @JvmName("output") internal fun output(session: ChatPanelSession, action: Runnable) {
        requireMain(); requireActive(session); cancelInput(session)
        session.redraw?.cancel(); session.redraw = null; session.suspended = true
        val generation = ++session.outputGeneration
        sender.accept(session.player, join(List(options.lines()) { RichText.plain("") }))
        try { action.run() } catch (failure: RuntimeException) { session.suspended = false; throw failure }
        owner.after(Ticks.of(1)) {
            if (active(session) && session.suspended && generation == session.outputGeneration) {
                val view = build(session, session.pages.getValue(session.current!!))
                val footer = ArrayList<RichTextSegment>()
                append(footer, control(text(ChatPanelText.RESUME), null, view.run("@resume")))
                append(footer, control(text(ChatPanelText.CLOSE), null, view.run("@close")))
                sender.accept(session.player, ChatPanelView.indent(footer))
            }
        }
    }

    private fun compose(session: ChatPanelSession, page: Page, view: ChatPanelView): RichText {
        val lines = options.lines()
        val header = ArrayList(page.panel.title().segments())
        view.subtitle()?.let { header.add(RichTextSegment(" › ", MessageColor.DARK_GRAY, true, null, null)); header.addAll(it.segments()) }
        val title = ChatPanelLayout.limited(ChatPanelView.indent(header), options.width(), if (lines <= 10) 1 else 2)
        val statusText = statusLine(session, view)
        val status = if (statusText.plainText().trim().isEmpty()) emptyList() else ChatPanelLayout.limited(statusText, options.width(), if (lines <= 10) 1 else 3)
        val navigation = ChatPanelLayout.wrap(footer(session, page, view, 2), options.width())
        val capacity = maxOf(1, lines - title.size - status.size - navigation.size - 3)
        val pages = paginate(view.blocks(), capacity)
        val count = maxOf(1, pages.size)
        page.index = page.index.coerceIn(0, count - 1)
        val out = ArrayList(title)
        out.add(RichText.plain(""))
        if (pages.isNotEmpty()) out.addAll(pages[page.index])
        out.add(RichText.plain("")); out.addAll(status)
        out.addAll(ChatPanelLayout.wrap(footer(session, page, view, count), options.width()))
        if (out.size < lines - 6) {
            while (out.size < lines - 7) out.add(RichText.plain(""))
            out.add(ChatPanelView.indent(text(ChatPanelText.EXPAND).segments()))
        }
        while (out.size < lines) out.add(RichText.plain(""))
        return join(out)
    }
    private fun statusLine(session: ChatPanelSession, view: ChatPanelView): RichText {
        val pending = session.pending ?: return session.status?.let { ChatPanelView.indent(it.segments()) } ?: RichText.plain("")
        val line = ArrayList<RichTextSegment>()
        session.status?.let { line.addAll(it.segments()); line.add(RichTextSegment.plain("\n  ")) }
        pending.input.hint()?.let { line.addAll(it.segments()); line.add(RichTextSegment.plain(" ")) }
        if (pending.sign) line.addAll(text(ChatPanelText.INPUT_SIGN_HINT).segments())
        else {
            line.addAll(text(ChatPanelText.INPUT_CHAT_HINT).segments()); line.add(RichTextSegment.plain(" "))
            if (pending.input.current().isNotEmpty()) line.addAll(control(text(ChatPanelText.PREFILL), text(ChatPanelText.PREFILL_HOVER),
                TextAction(TextAction.Type.SUGGEST_COMMAND, pending.input.current())))
        }
        line.add(RichTextSegment.plain(" ")); line.addAll(control(text(ChatPanelText.CANCEL_INPUT), null, view.run("@cancel")))
        return ChatPanelView.indent(line)
    }
    private fun footer(session: ChatPanelSession, page: Page, view: ChatPanelView, count: Int): RichText {
        val line = ArrayList<RichTextSegment>()
        if (count > 1) {
            line.addAll(control(text(ChatPanelText.PREVIOUS), null, if (page.index > 0) view.run("@page.${page.index - 1}") else null))
            line.add(RichTextSegment(" ${page.index + 1} / $count ", MessageColor.WHITE, false, null, null))
            line.addAll(control(text(ChatPanelText.NEXT), null, if (page.index + 1 < count) view.run("@page.${page.index + 1}") else null))
        }
        view.footer.forEach { append(line, view.render(it)) }
        if (session.history.isNotEmpty()) append(line, control(text(ChatPanelText.BACK), null, view.run("@back")))
        if (signs != null) {
            val sign = session.pending?.sign ?: prefersSign(session.player.uniqueId)
            append(line, control(text(if (sign) ChatPanelText.MODE_SIGN else ChatPanelText.MODE_CHAT), text(ChatPanelText.MODE_HOVER), view.run("@mode")))
        }
        append(line, control(text(ChatPanelText.CLOSE), null, view.run("@close")))
        return ChatPanelView.indent(line)
    }
    private fun signLines(current: String): Array<String> {
        val rows = ChatPanelLayout.wrap(RichText.plain(current), SIGN_LINE_WIDTH)
        return Array(4) { if (it == 3) text(ChatPanelText.SIGN_LINE).plainText() else rows.getOrNull(it)?.plainText() ?: "" }
    }
    private fun text(key: ChatPanelText): RichText = options.text(key)
    private fun active(session: ChatPanelSession): Boolean = !disposed && !owner.isClosed && !session.closed &&
        sessions[session.player.uniqueId] === session && clock.asLong - session.lastActive < options.idleMillis() && current(session.player)
    private fun requireActive(session: ChatPanelSession) { check(active(session)) { "chat panel session is no longer active" } }
    private fun current(player: Player): Boolean = player.isOnline && plugin.server.getPlayer(player.uniqueId) === player
    @JvmName("close") internal fun close(session: ChatPanelSession, clear: Boolean) {
        requireMain()
        if (session.closed) return
        session.closed = true; sessions.remove(session.player.uniqueId, session)
        session.redraw?.cancel(); session.redraw = null; cancelInput(session)
        if (clear && current(session.player)) {
            val blank = MutableList(options.lines()) { RichText.plain("") }
            blank[blank.lastIndex] = ChatPanelView.indent(text(ChatPanelText.CLOSED).segments())
            sender.accept(session.player, join(blank))
        }
    }
    private fun prune() { sessions.values.toList().filter { !active(it) }.forEach { close(it, false) } }
    @EventHandler fun quit(event: PlayerQuitEvent) { sessions[event.player.uniqueId]?.let { close(it, false) } }
    @EventHandler(ignoreCancelled = true) fun swap(event: PlayerSwapHandItemsEvent) {
        val session = sessions[event.player.uniqueId] ?: return
        if (session.player !== event.player || !active(session)) return
        val page = session.pages[session.current] ?: return
        if (!page.panel.hasHotkeys() || !page.panel.allowed(session.player)) return
        event.isCancelled = true; session.lastActive = clock.asLong; session.status = null
        val renders = session.renders
        session.acting++
        try { page.panel.hotkey(session, event.player.isSneaking) }
        catch (failure: RuntimeException) { fail(session, page, failure) }
        finally { session.acting-- }
        if (!session.closed) redrawUnlessRendered(session, renders)
    }
    private fun ensureOpen() { check(!disposed && !owner.isClosed) { "chat panels are closed" } }
    override fun dispose() {
        requireMain()
        if (disposed) return
        sessions.values.toList().forEach { close(it, false) }
        disposed = true; inputModes.clear(); HandlerList.unregisterAll(this)
    }

    companion object {
        private const val LOG = "chat-panel"
        private const val SIGN_LINE_WIDTH = 90
        @JvmStatic fun install(owner: Scope, plugin: Plugin, command: String, prompts: BukkitChatPrompts): BukkitChatPanels =
            install(owner, plugin, command, prompts, ChatPanelOptions.defaults())
        @JvmStatic fun install(owner: Scope, plugin: Plugin, command: String, prompts: BukkitChatPrompts, options: ChatPanelOptions): BukkitChatPanels {
            require(command.matches(Regex("[a-z][a-z0-9_-]{0,63}"))) { "invalid chat panel command label" }
            check(Bukkit.isPrimaryThread() && !owner.isClosed) { "install chat panels at plugin startup on the main thread" }
            val logger = owner.findCapability(KLogger::class.java).orElseGet { KLogger(plugin.logger) }
            val sender = options.sender() ?: run {
                val router = owner.install(BukkitMessageRouter(plugin.server))
                BiConsumer<Player, RichText> { player, message -> router.route(MessageRecipient.of(player, false), message) }
            }
            val signs = if (!options.signInput()) null else options.signs() ?: PaperSignInput.detect(owner, plugin, logger)
            val panels = owner.install(BukkitChatPanels(owner, plugin, command, prompts, options, sender, signs, logger, LongSupplier(System::currentTimeMillis)))
            val page = Arguments.string("page")
            val action = Arguments.string("action")
            owner.command(command) { root -> root.playerOnly().argument(page).argument(action).executes { context ->
                panels.dispatch(context.sender() as Player, context.get(page), context.get(action))
            } }
            plugin.server.pluginManager.registerEvents(panels, plugin)
            owner.every(Ticks.seconds(1)) { panels.prune() }
            return panels
        }
        private fun show(session: ChatPanelSession, page: Page) { session.suspended = false; session.current = page.key; session.navigation++ }
        private fun join(lines: List<RichText>): RichText {
            val joined = ArrayList<RichTextSegment>()
            lines.forEachIndexed { index, line -> if (index > 0) joined.add(RichTextSegment.plain("\n")); joined.addAll(line.segments()) }
            return RichText(joined)
        }
        @JvmStatic @JvmName("paginate") internal fun paginate(blocks: List<List<RichText>>, capacity: Int): List<List<RichText>> {
            val pages = ArrayList<List<RichText>>()
            var current = ArrayList<RichText>()
            for (block in blocks) {
                if (block.isEmpty()) continue
                val needed = block.size + if (current.isEmpty()) 0 else 1
                if (current.isNotEmpty() && current.size + needed > capacity) { pages.add(current); current = ArrayList() }
                if (current.isNotEmpty()) current.add(RichText.plain(""))
                for (line in block) {
                    if (current.size >= capacity) { pages.add(current); current = ArrayList() }
                    current.add(line)
                }
            }
            if (current.isNotEmpty()) pages.add(current)
            return pages
        }
        private fun append(line: MutableList<RichTextSegment>, button: List<RichTextSegment>) {
            if (line.isNotEmpty()) line.add(RichTextSegment.plain(" ")); line.addAll(button)
        }
        private fun control(label: RichText, hover: RichText?, click: TextAction?): List<RichTextSegment> {
            val tip = ChatPanelView.hover(hover)
            val out = ArrayList<RichTextSegment>()
            out.add(RichTextSegment("[", MessageColor.DARK_GRAY, false, tip, click))
            if (click == null) out.add(RichTextSegment(label.plainText(), MessageColor.DARK_GRAY, false, null, null))
            else out.addAll(ChatPanelView.decorate(label.segments(), null, tip, click))
            out.add(RichTextSegment("]", MessageColor.DARK_GRAY, false, tip, click))
            return out
        }
        private fun fitsSign(input: ChatPanelInput<*>): Boolean = input.current().let {
            '\n' !in it && '\r' !in it && ChatPanelLayout.wrap(RichText.plain(it), SIGN_LINE_WIDTH).size <= 3
        }
        private fun allowed(player: Player, permission: String): Boolean = permission.isEmpty() || player.hasPermission(permission)
        private fun requireMain() { check(Bukkit.isPrimaryThread()) { "chat panels require the primary thread" } }
    }
}
