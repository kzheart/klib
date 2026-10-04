package me.kzheart.klib.script;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import me.kzheart.klib.scope.Disposable;
import me.kzheart.klib.scope.ScopeImpl;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Scoreboard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BukkitScriptHostTest {
    private FakeBukkitScriptServer server;
    private ScopeImpl scope;
    private Player player;

    @BeforeEach void setup() throws ReflectiveOperationException {
        server = new FakeBukkitScriptServer();
        scope = new ScopeImpl("script-host-test");
        player = server.player();
    }

    @AfterEach void cleanup() throws ReflectiveOperationException {
        try { scope.close(); }
        finally { server.close(); }
    }

    @Test void optionalServicesAreDisabledByDefaultAndBukkitServicesAreShared() {
        BukkitScriptHost host = builder().build();
        ScriptContext first = context(host);
        ScriptContext second = context(host);
        assertFalse(first.service(JavaScriptEvaluator.class).isPresent());
        assertSame(first.requireService(ScriptPlatform.class), second.requireService(ScriptPlatform.class));
        assertThrows(UnsupportedOperationException.class,
                () -> first.requireService(ScriptPlatform.class).scoreboard(player, Arrays.asList("Title", "line")));
    }

    @Test void javascriptFactoryIsLazySharedAndDisposedExactlyOnce() {
        AtomicInteger created = new AtomicInteger();
        CloseableEvaluator evaluator = new CloseableEvaluator();
        BukkitScriptHost host = builder().javascript(() -> { created.incrementAndGet(); return evaluator; }).build();
        JavaScriptEvaluator first = context(host).requireService(JavaScriptEvaluator.class);
        JavaScriptEvaluator second = context(host).requireService(JavaScriptEvaluator.class);
        assertEquals(0, created.get());
        assertEquals("first", first.eval("first", Collections.<String, Object>emptyMap()));
        assertEquals("second", second.eval("second", Collections.<String, Object>emptyMap()));
        assertEquals(1, created.get());
        scope.close();
        host.close();
        assertEquals(1, evaluator.closed);
        assertThrows(IllegalStateException.class, () -> first.eval("closed", Collections.<String, Object>emptyMap()));
    }

    @Test void absentJavascriptFailsAtFirstActionWithDiagnostic() {
        BukkitScriptHost host = builder().javascriptEngine("__missing_klib_test_engine__").build();
        JavaScriptEvaluator evaluator = context(host).requireService(JavaScriptEvaluator.class);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> evaluator.eval("1 + 1", Collections.<String, Object>emptyMap()));
        assertTrue(failure.getMessage().contains("could not start"));
        assertTrue(failure.getCause().getMessage().contains("__missing_klib_test_engine__"));
    }

    @Test void closingUnusedJavascriptNeverInvokesTheFactory() {
        AtomicInteger created = new AtomicInteger();
        builder().javascript(() -> { created.incrementAndGet(); return (source, bindings) -> source; }).build();
        scope.close();
        assertEquals(0, created.get());
    }

    @Test void buildingAgainstClosedScopeDoesNotLeaveListenersRegistered() {
        scope.close();
        assertThrows(IllegalStateException.class, () -> builder().build());
        assertTrue(HandlerList.getRegisteredListeners(server.plugin).isEmpty());
    }

    @Test void nativeSidebarKeepsDuplicateEmptyRowsAndUpdatesWithoutLosingPreviousBoard() {
        Scoreboard previous = player.getScoreboard();
        ScriptPlatform platform = platform(builder().scoreboard(true).build());
        platform.scoreboard(player, Arrays.asList("Title", "same", "", "same", ""));
        Scoreboard board = player.getScoreboard();
        assertNotSame(previous, board);
        assertEquals("Title", board.getObjective(DisplaySlot.SIDEBAR).getDisplayName());
        assertEquals(Arrays.asList("same", "", "same", ""), FakeBukkitScriptServer.rows(board));
        platform.scoreboard(player, Arrays.asList("Changed", "new"));
        assertSame(board, player.getScoreboard());
        assertEquals(Collections.singletonList("new"), FakeBukkitScriptServer.rows(board));
        assertEquals(1, board.getTeams().size());
        platform.scoreboard(player, null);
        assertSame(previous, player.getScoreboard());
        assertTrue(board.getObjectives().isEmpty());
        assertTrue(board.getTeams().isEmpty());
        platform.scoreboard(player, null);
        assertSame(previous, player.getScoreboard());
    }

    @Test void nativeSidebarEmptyListClearsAndExcessRowsFailBeforeReplacingTheBoard() {
        Scoreboard previous = player.getScoreboard();
        ScriptPlatform platform = platform(builder().scoreboard(true).build());
        platform.scoreboard(player, Collections.singletonList("Title only"));
        assertTrue(FakeBukkitScriptServer.rows(player.getScoreboard()).isEmpty());
        platform.scoreboard(player, Collections.<String>emptyList());
        assertSame(previous, player.getScoreboard());
        List<String> excessive = new ArrayList<String>(Collections.nCopies(17, "line"));
        assertThrows(IllegalArgumentException.class, () -> platform.scoreboard(player, excessive));
        assertSame(previous, player.getScoreboard());
    }

    @Test void otherPluginsScoreboardsArePreservedOnRemoveAndNewOwnershipPeriod() {
        ScriptPlatform platform = platform(builder().scoreboard(true).build());
        platform.scoreboard(player, Arrays.asList("First", "line"));
        Scoreboard owned = player.getScoreboard();
        Scoreboard other = FakeBukkitScriptServer.board();
        player.setScoreboard(other);
        platform.scoreboard(player, null);
        assertSame(other, player.getScoreboard());
        assertTrue(owned.getObjectives().isEmpty());
        platform.scoreboard(player, Arrays.asList("Second", "line"));
        Scoreboard newer = FakeBukkitScriptServer.board();
        player.setScoreboard(newer);
        platform.scoreboard(player, Arrays.asList("Third", "line"));
        scope.close();
        assertSame(newer, player.getScoreboard());
    }

    @Test void scopeCloseRestoresOwnedBoardsButNotExternalReplacementsAndUnregistersListeners() {
        Player second = server.player();
        Scoreboard previous = player.getScoreboard();
        BukkitScriptHost host = builder().scoreboard(true).build();
        ScriptPlatform platform = platform(host);
        platform.scoreboard(player, Arrays.asList("One", "line"));
        platform.scoreboard(second, Arrays.asList("Two", "line"));
        Scoreboard other = FakeBukkitScriptServer.board();
        second.setScoreboard(other);
        assertEquals(2, HandlerList.getRegisteredListeners(server.plugin).size());
        scope.close();
        assertSame(previous, player.getScoreboard());
        assertSame(other, second.getScoreboard());
        assertTrue(HandlerList.getRegisteredListeners(server.plugin).isEmpty());
        assertThrows(IllegalStateException.class, () -> platform.onlinePlayerNames());
        assertThrows(IllegalStateException.class, () -> context(host));
    }

    @Test void quitAndDisableCleanupOwnedSidebars() throws Exception {
        Scoreboard previous = player.getScoreboard();
        BukkitScriptHost host = builder().scoreboard(true).build();
        ScriptPlatform platform = platform(host);
        platform.scoreboard(player, Arrays.asList("One", "line"));
        FakeBukkitScriptServer.fire(new PlayerQuitEvent(player, "quit"));
        assertSame(previous, player.getScoreboard());
        platform.scoreboard(player, Arrays.asList("Again", "line"));
        FakeBukkitScriptServer.fire(new PluginDisableEvent(server.plugin));
        assertSame(previous, player.getScoreboard());
        assertThrows(IllegalStateException.class, () -> context(host));
    }

    @Test void callbackScoreboardDoesNotTakeNativeOwnershipAndReceivesNullForClear() {
        AtomicReference<List<String>> received = new AtomicReference<List<String>>();
        Scoreboard initial = player.getScoreboard();
        BukkitScriptHost host = builder().scoreboard(true).scoreboard((target, lines) -> {
            assertSame(player, target);
            received.set(lines);
        }).build();
        ScriptPlatform platform = platform(host);
        List<String> lines = Arrays.asList("Callback", "line");
        platform.scoreboard(player, lines);
        assertSame(lines, received.get());
        assertSame(initial, player.getScoreboard());
        platform.scoreboard(player, null);
        assertNull(received.get());
        scope.close();
        assertSame(initial, player.getScoreboard());
    }

    @Test void nativeScoreboardOptionOverridesAnEarlierCallback() {
        AtomicInteger called = new AtomicInteger();
        ScriptPlatform platform = platform(builder().scoreboard((target, lines) -> called.incrementAndGet()).scoreboard(true).build());
        platform.scoreboard(player, Arrays.asList("Native", "line"));
        assertEquals(Collections.singletonList("line"), FakeBukkitScriptServer.rows(player.getScoreboard()));
        assertEquals(0, called.get());
    }

    @Test void servicesRejectAsyncCallsBeforeInvokingBukkitOrJavascript() {
        AtomicInteger invoked = new AtomicInteger();
        BukkitScriptHost host = builder().javascript(() -> { invoked.incrementAndGet(); return (s, b) -> s; }).build();
        ScriptContext context = context(host);
        CompletionException failure = assertThrows(CompletionException.class,
                () -> CompletableFuture.runAsync(() -> context.requireService(ScriptPlatform.class).onlinePlayerNames()).join());
        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertThrows(CompletionException.class,
                () -> CompletableFuture.runAsync(() -> context.requireService(JavaScriptEvaluator.class)
                        .eval("no", Collections.<String, Object>emptyMap())).join());
        assertEquals(0, invoked.get());
    }

    @Test void delayCompletesNormallyAndReleasesItsTask() {
        DelayScheduler scheduler = context(builder().build()).requireService(DelayScheduler.class);
        CompletableFuture<Object> future = scheduler.delay(Duration.ofMillis(100)).toCompletableFuture();
        assertEquals(1, server.pendingTasks());
        server.advance(1);
        assertFalse(future.isDone());
        server.advance(1);
        assertTrue(future.isDone());
        assertNull(future.join());
        assertEquals(0, server.pendingTasks());
        scope.close();
        assertEquals(0, server.cancelledTasks());
    }

    @Test void delayCancellationCancelsItsTaskOnceAndNeverContinues() {
        DelayScheduler scheduler = context(builder().build()).requireService(DelayScheduler.class);
        AtomicInteger continued = new AtomicInteger();
        CompletableFuture<Object> future = scheduler.delay(Duration.ofSeconds(10)).toCompletableFuture();
        future.thenRun(continued::incrementAndGet);
        future.cancel(false);
        future.cancel(false);
        assertTrue(future.isCancelled());
        assertEquals(0, server.pendingTasks());
        assertEquals(1, server.cancelledTasks());
        server.advance(250);
        scope.close();
        assertEquals(0, continued.get());
        assertEquals(1, server.cancelledTasks());
    }

    @Test void closingScopeCancelsAllPendingDelayTasksAndFutures() {
        DelayScheduler scheduler = context(builder().build()).requireService(DelayScheduler.class);
        CompletableFuture<Object> first = scheduler.delay(Duration.ofSeconds(10)).toCompletableFuture();
        CompletableFuture<Object> second = scheduler.delay(Duration.ofSeconds(20)).toCompletableFuture();
        AtomicInteger continued = new AtomicInteger();
        first.thenRun(continued::incrementAndGet);
        second.thenRun(continued::incrementAndGet);
        scope.close();
        assertTrue(first.isCancelled());
        assertTrue(second.isCancelled());
        assertEquals(0, server.pendingTasks());
        assertEquals(2, server.cancelledTasks());
        server.advance(500);
        assertEquals(0, continued.get());
        assertThrows(IllegalStateException.class, () -> scheduler.delay(Duration.ofSeconds(1)));
    }

    @Test void asynchronousDelayCancellationIsCleanedOnThePrimaryThread() {
        DelayScheduler scheduler = context(builder().build()).requireService(DelayScheduler.class);
        CompletableFuture<Object> future = scheduler.delay(Duration.ofSeconds(10)).toCompletableFuture();
        CompletableFuture.runAsync(() -> future.cancel(false)).join();
        assertTrue(future.isCancelled());
        server.advance(1);
        assertEquals(0, server.pendingTasks());
        assertEquals(1, server.cancelledTasks());
    }

    @Test void closingScopeAlsoCancelsAnAlreadyQueuedCancellationDispatch() {
        DelayScheduler scheduler = context(builder().build()).requireService(DelayScheduler.class);
        CompletableFuture<Object> future = scheduler.delay(Duration.ofSeconds(10)).toCompletableFuture();
        CompletableFuture.runAsync(() -> future.cancel(false)).join();
        assertEquals(2, server.pendingTasks());
        scope.close();
        assertEquals(0, server.pendingTasks());
        assertEquals(2, server.cancelledTasks());
        server.advance(250);
        assertEquals(0, server.pendingTasks());
    }

    private BukkitScriptHost.Builder builder() { return BukkitScriptHost.builder(server.plugin, scope); }
    private ScriptContext context(BukkitScriptHost host) { return host.apply(ScriptContext.builder()).build(); }
    private ScriptPlatform platform(BukkitScriptHost host) { return context(host).requireService(ScriptPlatform.class); }

    private static final class CloseableEvaluator implements JavaScriptEvaluator, Disposable {
        private int closed;
        @Override public Object eval(String source, Map<String, Object> bindings) { return source; }
        @Override public void dispose() { closed++; }
    }
}
