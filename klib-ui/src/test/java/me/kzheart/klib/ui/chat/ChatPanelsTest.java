package me.kzheart.klib.ui.chat;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.util.function.Supplier;
import me.kzheart.klib.scope.Scope;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import me.kzheart.klib.lang.RichText;
import me.kzheart.klib.lang.RichTextSegment;
import me.kzheart.klib.lang.TextAction;
import me.kzheart.klib.scope.ScopeImpl;
import me.kzheart.klib.KLogger;
import me.kzheart.klib.scheduler.KScheduler;
import me.kzheart.klib.scheduler.SchedulerFactory;
import me.kzheart.klib.scheduler.AsyncTask;
import me.kzheart.klib.scheduler.TaskHandle;
import me.kzheart.klib.scheduler.Ticks;
import me.kzheart.klib.ui.prompt.BukkitChatPrompts;
import me.kzheart.klib.ui.prompt.PromptSpec;
import me.kzheart.klib.ui.prompt.PromptSession;
import me.kzheart.klib.ui.prompt.PromptStatus;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatPanelsTest {
    @Test void inputCompletionReturnsToMainThreadAndRejectsAChangedTarget() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicBoolean unchanged = new AtomicBoolean(true);
            AtomicInteger applied = new AtomicInteger();
            ChatPanelSession session = f.panels.open(f.player, ChatPanel.builder(RichText.plain("字段"))
                    .guard(p -> unchanged.get()).build());
            PromptSession<String> input = session.input(PromptSpec.<String>builder(Optional::of).build(), "原值",
                    value -> applied.incrementAndGet(), () -> {});
            Thread chat = new Thread(() -> input.submit("新值"));
            chat.start(); chat.join();
            assertEquals(0, applied.get());
            unchanged.set(false);
            f.drain();
            assertEquals(0, applied.get());
            assertTrue(session.isClosed());
        }
    }

    @Test void replacingPanelCancelsOnlyItsOwnPendingInput() throws Exception {
        try (Fixture f = new Fixture()) {
            ChatPanel model = ChatPanel.builder(RichText.plain("面板")).build();
            ChatPanelSession first = f.panels.open(f.player, model);
            PromptSession<String> input = first.input(PromptSpec.<String>builder(Optional::of).build(), "原值", value -> fail("old input invoked"), () -> {});
            f.panels.open(f.player, model);
            assertEquals(PromptStatus.CANCELLED, input.status());
            f.drain();
            assertFalse(input.submit("迟到内容"));
        }
    }
    @Test void staleButtonsAndOtherPlayersCannotRepeatAnAction() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger actions = new AtomicInteger();
            f.panels.open(f.player, ChatPanel.builder(RichText.plain("控制面板"))
                    .row(RichText.plain("状态"), ChatPanelButton.action(RichText.plain("切换"), s -> actions.incrementAndGet())).build());
            String[] token = f.token();
            f.panels.dispatch(f.other, token[1], token[2]);
            assertEquals(0, actions.get());
            f.panels.dispatch(f.player, token[1], token[2]);
            f.panels.dispatch(f.player, token[1], token[2]);
            assertEquals(1, actions.get());
            assertNotEquals(token[1], f.token()[1]);
        }
    }

    @Test void permissionAndBusinessGuardAreRecheckedAtClickTime() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger actions = new AtomicInteger();
            AtomicBoolean unchanged = new AtomicBoolean(true);
            ChatPanel model = ChatPanel.builder(RichText.plain("物品"))
                    .guard(p -> unchanged.get())
                    .row(RichText.plain("值"), ChatPanelButton.action(RichText.plain("修改"), s -> actions.incrementAndGet()).permission("edit"))
                    .build();
            f.panels.open(f.player, model);
            String[] token = f.token();
            f.permitted = false;
            f.panels.dispatch(f.player, token[1], token[2]);
            assertEquals(0, actions.get());
            f.permitted = true;
            unchanged.set(false);
            f.panels.dispatch(f.player, token[1], token[2]);
            assertEquals(0, actions.get());
        }
    }

    @Test void expiryReplacementAndScopeCloseRevokeButtons() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger actions = new AtomicInteger();
            ChatPanel model = ChatPanel.builder(RichText.plain("面板")).lifetimeMillis(1000)
                    .row(RichText.plain("值"), ChatPanelButton.action(RichText.plain("执行"), s -> actions.incrementAndGet())).build();
            ChatPanelSession first = f.panels.open(f.player, model);
            String[] old = f.token();
            f.now = 1001;
            f.panels.dispatch(f.player, old[1], old[2]);
            assertEquals(0, actions.get());
            f.panels.open(f.player, model);
            assertTrue(first.isClosed());
            String[] replacement = f.token();
            f.scope.close();
            f.panels.dispatch(f.player, replacement[1], replacement[2]);
            assertEquals(0, actions.get());
        }
    }

    @Test void paginationAndLiteralPrefillPreserveDataWithoutExecutingIt() throws Exception {
        try (Fixture f = new Fixture()) {
            ChatPanelSession session = f.panels.open(f.player, ChatPanel.builder(RichText.plain("列表")).pageSize(1)
                    .row(RichText.plain("第一行"), ChatPanelButton.suggest(RichText.plain("预填"), "<click:run_command:'/op test'>原文"))
                    .row(RichText.plain("第二行"), ChatPanelButton.copy(RichText.plain("复制"), "{id:1}")).build());
            assertEquals(0, session.page());
            assertTrue(f.sent.stream().flatMap(t -> t.segments().stream()).anyMatch(s -> s.click() != null
                    && s.click().type() == TextAction.Type.SUGGEST_COMMAND && s.click().value().startsWith("<click:")));
            session.page(1);
            assertEquals(1, session.page());
            assertTrue(f.sent.stream().flatMap(t -> t.segments().stream()).anyMatch(s -> s.click() != null && s.click().type() == TextAction.Type.COPY_TO_CLIPBOARD));
        }
    }

    @Test void offMainThreadOperationsAreRejected() throws Exception {
        try (Fixture f = new Fixture()) {
            f.primary = false;
            assertThrows(IllegalStateException.class, () -> f.panels.open(f.player, ChatPanel.builder(RichText.plain("面板")).build()));
            f.primary = true;
        }
    }

    static final class Fixture implements AutoCloseable {
        final ScopeImpl scope = new ScopeImpl("chat-test");
        final List<RichText> sent = new ArrayList<RichText>();
        final List<Runnable> work = new ArrayList<Runnable>();
        final UUID id = UUID.randomUUID();
        final Player player;
        final Player other;
        final Plugin plugin;
        final BukkitChatPanels panels;
        final Field serverField;
        final Object previousServer;
        boolean primary = true;
        boolean permitted = true;
        long now;
        Fixture() throws Exception {
            player = player(id);
            other = player(UUID.randomUUID());
            Server server = proxy(Server.class, (p, method, args) -> {
                if (method.getName().equals("isPrimaryThread")) return Boolean.valueOf(primary);
                if (method.getName().equals("getPlayer")) return args[0].equals(id) ? player : other;
                return defaultValue(method.getReturnType());
            });
            plugin = proxy(Plugin.class, (p, method, args) -> {
                if (method.getName().equals("getServer")) return server;
                if (method.getName().equals("getLogger")) return Logger.getLogger("chat-test");
                return defaultValue(method.getReturnType());
            });
            serverField = Bukkit.class.getDeclaredField("server"); serverField.setAccessible(true);
            previousServer = serverField.get(null); serverField.set(null, server);
            scope.registerCapability(SchedulerFactory.class, ignored -> new KScheduler() {
                @Override public TaskHandle every(Ticks period, Runnable task) { throw new UnsupportedOperationException(); }
                @Override public TaskHandle after(Ticks delay, Runnable task) {
                    NoopTask handle = new NoopTask();
                    if (delay.value() == 0) work.add(() -> { if (!handle.cancelled) task.run(); });
                    return handle;
                }
                @Override public <T> AsyncTask<T> async(Supplier<T> supplier) { throw new UnsupportedOperationException(); }
            });
            Constructor<BukkitChatPrompts> constructor = BukkitChatPrompts.class
                    .getDeclaredConstructor(Scope.class, Plugin.class, KLogger.class);
            constructor.setAccessible(true);
            BukkitChatPrompts prompts = scope.install(constructor.newInstance(scope, plugin, new KLogger(plugin.getLogger())));
            panels = scope.install(new BukkitChatPanels(scope, plugin, "testpanel", prompts, (p, message) -> sent.add(message), () -> now));
        }
        void drain() { while (!work.isEmpty()) { List<Runnable> queued = new ArrayList<Runnable>(work); work.clear(); queued.forEach(Runnable::run); } }
        private Player player(UUID identity) {
            return proxy(Player.class, (p, method, args) -> {
                if (method.getName().equals("getUniqueId")) return identity;
                if (method.getName().equals("isOnline")) return Boolean.TRUE;
                if (method.getName().equals("hasPermission")) return Boolean.valueOf(permitted);
                return defaultValue(method.getReturnType());
            });
        }
        String[] token() {
            for (int i = sent.size() - 1; i >= 0; i--) for (RichTextSegment segment : sent.get(i).segments())
                if (segment.click() != null && segment.click().type() == TextAction.Type.RUN_COMMAND)
                    return segment.click().value().split(" ");
            throw new AssertionError("missing callback button");
        }
        @Override public void close() throws ReflectiveOperationException {
            primary = true;
            try { scope.close(); } finally { serverField.set(null, previousServer); }
        }
        private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
        }
        private static Object defaultValue(Class<?> type) {
            if (type == Boolean.TYPE) return Boolean.FALSE;
            if (type == Integer.TYPE) return Integer.valueOf(0);
            if (type == Long.TYPE) return Long.valueOf(0);
            if (type == Double.TYPE) return Double.valueOf(0);
            if (type == Float.TYPE) return Float.valueOf(0);
            return null;
        }
        private static final class NoopTask implements TaskHandle {
            boolean cancelled;
            @Override public boolean cancel() { cancelled = true; return true; }
            @Override public boolean isCancelled() { return cancelled; }
            @Override public boolean isDone() { return cancelled; }
        }
    }
}
