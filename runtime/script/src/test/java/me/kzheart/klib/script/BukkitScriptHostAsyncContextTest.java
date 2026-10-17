package me.kzheart.klib.script;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import me.kzheart.klib.scope.ScopeImpl;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.*;

/** 异步纯表达式可以装配上下文，但不可借此跨过 Bukkit 服务的线程及生命周期边界。 */
@Timeout(20)
class BukkitScriptHostAsyncContextTest {
    private static final List<Class<?>> SERVICE_TYPES = Arrays.<Class<?>>asList(
            MessageSink.class, CommandSink.class, ScriptSenderQuery.class, PlayerQuery.class,
            DelayScheduler.class, ScriptLogger.class, ScriptPlatform.class,
            ScriptPropertyAccess.class, JavaScriptEvaluator.class);
    private static final int WORKERS = 6;

    private final AtomicInteger offThreadCalls = new AtomicInteger();
    private final AtomicInteger javascriptFactories = new AtomicInteger();
    private final AtomicInteger javascriptEvaluations = new AtomicInteger();
    private final AtomicInteger scoreboardCallbacks = new AtomicInteger();
    private final List<String> messages = new ArrayList<String>();
    private FakeBukkitScriptServer server;
    private ScopeImpl scope;
    private ExecutorService workers;
    private Thread primary;
    private Plugin plugin;
    private Player player;
    private ItemMeta itemMeta;

    @BeforeEach void setup() throws ReflectiveOperationException {
        primary = Thread.currentThread();
        server = new FakeBukkitScriptServer();
        scope = new ScopeImpl("script-host-async-context-test");
        plugin = observed(Plugin.class, server.plugin);
        player = observed(Player.class, server.player());
        itemMeta = ItemMeta.class.cast(Proxy.newProxyInstance(ItemMeta.class.getClassLoader(),
                new Class<?>[]{ItemMeta.class}, (proxy, method, arguments) -> {
                    throw new AssertionError("Property access reached ItemMeta: " + method.getName());
                }));
        // 在现有夹具外侧观测调用，不修改共享 FakeBukkitScriptServer 的行为。
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(null, observed(Server.class, Bukkit.getServer()));
        workers = Executors.newFixedThreadPool(WORKERS, task -> {
            Thread worker = new Thread(task, "script-host-context-worker");
            worker.setDaemon(true);
            return worker;
        });
    }

    @AfterEach void cleanup() throws Exception {
        try {
            if (workers != null) {
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "Context workers did not stop");
            }
        } finally {
            try { if (scope != null) scope.close(); }
            finally { if (server != null) server.close(); }
        }
    }

    @Test void parallelContextsShareOnlyServicesAndEvaluateNativeMathAndVariables() throws Exception {
        BukkitScriptHost host = host();
        ScriptContext reference = context(host);
        ScopedScriptRuntime runtime = runtime();
        int perWorker = 16;
        Object[] senders = new Object[WORKERS * perWorker];
        for (int index = 0; index < senders.length; index++) senders[index] = new Object();
        CountDownLatch ready = new CountDownLatch(WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<ScriptContext>>> results = new ArrayList<Future<List<ScriptContext>>>();
        for (int worker = 0; worker < WORKERS; worker++) {
            final int first = worker * perWorker;
            results.add(workers.submit(() -> {
                ready.countDown();
                await(start);
                List<ScriptContext> contexts = new ArrayList<ScriptContext>();
                for (int offset = 0; offset < perWorker; offset++) {
                    int id = first + offset;
                    ScriptContext.Builder builder = ScriptContext.builder().sender(senders[id])
                            .variable("seed", Integer.valueOf(id)).variable("owner", "owner-" + id);
                    assertSame(builder, host.apply(builder));
                    ScriptContext context = builder.build();
                    assertComplete(reference, context);
                    assertSame(senders[id], context.sender().orElse(null));
                    assertEquals(Integer.valueOf(id + 1), runtime.eval(
                            "set result to math add [ &seed 1 ]\n&result", context).toCompletableFuture().join());
                    assertEquals("owner-" + id, runtime.eval(
                            "set copied to &owner\n&copied", context).toCompletableFuture().join());
                    context.setVariable("local", Integer.valueOf(id));
                    contexts.add(context);
                }
                return contexts;
            }));
        }
        await(ready);
        start.countDown();
        List<ScriptContext> contexts = new ArrayList<ScriptContext>();
        for (Future<List<ScriptContext>> result : results) contexts.addAll(result.get(5, TimeUnit.SECONDS));
        assertEquals(senders.length, contexts.size());
        for (int id = 0; id < contexts.size(); id++) {
            ScriptContext context = contexts.get(id);
            assertSame(senders[id], context.sender().orElse(null));
            assertEquals(Integer.valueOf(id), context.variableOrNull("seed"));
            assertEquals(Integer.valueOf(id + 1), context.variableOrNull("result"));
            assertEquals("owner-" + id, context.variableOrNull("copied"));
            assertEquals(Integer.valueOf(id), context.variableOrNull("local"));
        }
        contexts.get(0).setVariable("local", "changed");
        assertEquals(Integer.valueOf(1), contexts.get(1).variableOrNull("local"));
        assertTrue(reference.variables().isEmpty());
        assertEquals(0, runtime.activeExecutionCount());
        assertNoSideEffects();
    }

    @Test void workerInstallationPreservesDynamicSenderAndOptionalServiceAbsence() throws Exception {
        BukkitScriptHost host = BukkitScriptHost.builder(plugin, scope).build();
        ScriptContext context = workers.submit(() -> host.apply(ScriptContext.builder()
                .sender(new Object()).senderVariable("selected").variable("selected", player)).build())
                .get(5, TimeUnit.SECONDS);
        assertSame(player, context.sender().orElse(null));
        assertFalse(context.service(JavaScriptEvaluator.class).isPresent());
        Object replacement = new Object();
        context.setVariable("selected", replacement);
        assertSame(replacement, context.sender().orElse(null));
        context.removeVariable("selected");
        assertFalse(context.sender().isPresent());
        for (Class<?> type : SERVICE_TYPES) {
            if (type != JavaScriptEvaluator.class) assertTrue(context.service(type).isPresent(), type.getName());
        }
        assertNoSideEffects();
    }

    @Test void workerBuiltContextExecutesBukkitScriptsOnMainThreadUntilHostCloses() throws Exception {
        BukkitScriptHost host = host();
        ScopedScriptRuntime runtime = new ScopedScriptRuntime(scope, new StatementRegistry(), null,
                Runnable::run, true);
        ScriptContext context = workers.submit(() -> host.apply(ScriptContext.builder()
                .sender(player).variable("owner", "worker-context")).build()).get(5, TimeUnit.SECONDS);
        Map<String, Executable> retained = serviceCalls(context);
        assertNoSideEffects();

        assertEquals("Alex", runtime.eval("tell before-wait\nplayer name", context).toCompletableFuture().join());
        assertEquals(Collections.singletonList("before-wait"), messages);
        assertEquals("worker-context", context.variableOrNull("owner"));
        assertEquals("first", runtime.eval("js 'first'", context).toCompletableFuture().join());
        assertEquals("second", runtime.eval("js 'second'", context).toCompletableFuture().join());
        assertEquals(1, javascriptFactories.get());
        assertEquals(2, javascriptEvaluations.get());

        CompletableFuture<Object> delayed = runtime.eval("wait 1s\ntell after-wait", context).toCompletableFuture();
        assertFalse(delayed.isDone());
        assertEquals(1, server.pendingTasks());
        server.advance(19);
        assertFalse(delayed.isDone());
        assertEquals(Collections.singletonList("before-wait"), messages);
        server.advance(1);
        assertEquals("after-wait", delayed.get(5, TimeUnit.SECONDS));
        assertEquals(Arrays.asList("before-wait", "after-wait"), messages);
        assertEquals(0, server.pendingTasks());
        assertEquals(0, runtime.activeExecutionCount());

        host.close();
        assertRejectedServiceCalls(retained, "closed");
        assertEquals(Arrays.asList("before-wait", "after-wait"), messages);
        assertEquals(1, javascriptFactories.get());
        assertEquals(2, javascriptEvaluations.get());
        assertEquals(0, scoreboardCallbacks.get());
        assertEquals(0, offThreadCalls.get());
        assertEquals(0, server.pendingTasks());
        assertEquals(0, server.cancelledTasks());
        assertTrue(HandlerList.getRegisteredListeners(plugin).isEmpty());
    }

    @Test void everyServiceRejectsWorkerCallsBeforeBukkitCallbacksOrLazyJavascript() throws Exception {
        BukkitScriptHost host = host();
        workers.submit(() -> {
            ScriptContext context = host.apply(ScriptContext.builder().sender(player)).build();
            assertRejectedServiceCalls(context, "main thread");
        }).get(5, TimeUnit.SECONDS);
        assertNoSideEffects();
    }

    @Test void workerScriptsReachGuardsInsteadOfInvokingBukkitOrJavascript() throws Exception {
        BukkitScriptHost host = host();
        ScopedScriptRuntime runtime = runtime();
        List<String> sources = Arrays.asList("tell forbidden", "command 'say forbidden'",
                "command 'say forbidden' as op", "player level", "player level to 3",
                "permission forbidden", "sender", "players", "scoreboard array [ Title line ]",
                "js '1 + 1'", "wait 1s", "log forbidden", "get property name from &meta",
                "set property name from &meta to *forbidden");
        workers.submit(() -> {
            for (String source : sources) {
                ScriptContext context = host.apply(ScriptContext.builder().sender(player)
                        .variable("meta", itemMeta)).build();
                CompletionException failure = assertThrows(CompletionException.class,
                        () -> runtime.eval(source, context).toCompletableFuture().join(), source);
                Throwable cause = failure;
                while (cause.getCause() != null) cause = cause.getCause();
                assertTrue(cause instanceof IllegalStateException, source + ": " + cause);
                assertTrue(cause.getMessage().contains("main thread"), source + ": " + cause);
            }
        }).get(5, TimeUnit.SECONDS);
        assertEquals(0, runtime.activeExecutionCount());
        assertNoSideEffects();
    }

    @Test void installationRacingMainThreadCloseIsCompleteOrRejectedWithoutPartialBuilders() throws Exception {
        for (int round = 0; round < 24; round++) {
            BukkitScriptHost host = host();
            ScriptContext reference = context(host);
            CountDownLatch installed = new CountDownLatch(WORKERS);
            CountDownLatch race = new CountDownLatch(1);
            CountDownLatch closed = new CountDownLatch(1);
            List<Future<?>> results = new ArrayList<Future<?>>();
            for (int worker = 0; worker < WORKERS; worker++) {
                final Object sender = new Object();
                results.add(workers.submit(() -> {
                    assertComplete(reference, host.apply(ScriptContext.builder().sender(sender)).build());
                    installed.countDown();
                    await(race);
                    for (int attempt = 0; attempt < 128; attempt++) {
                        ScriptContext.Builder builder = ScriptContext.builder().sender(sender)
                                .variable("marker", Integer.valueOf(attempt));
                        try {
                            host.apply(builder);
                        } catch (IllegalStateException failure) {
                            assertTrue(failure.getMessage().contains("closed"), failure.getMessage());
                            assertUninstalled(builder.build());
                            continue;
                        }
                        ScriptContext context = builder.build();
                        assertComplete(reference, context);
                        assertSame(sender, context.sender().orElse(null));
                        assertEquals(Integer.valueOf(attempt), context.variableOrNull("marker"));
                    }
                    await(closed);
                    assertClosedInstallation(host);
                }));
            }
            await(installed);
            race.countDown();
            host.close();
            closed.countDown();
            for (Future<?> result : results) result.get(5, TimeUnit.SECONDS);
            assertTrue(HandlerList.getRegisteredListeners(plugin).isEmpty());
        }
        assertNoSideEffects();
    }

    @Test void scopeCloseRejectsWorkerInstallationAndPreviouslyObtainedServicesOnMainThread() throws Exception {
        BukkitScriptHost host = host();
        Map<String, Executable> retained = workers.submit(() -> serviceCalls(context(host)))
                .get(5, TimeUnit.SECONDS);
        scope.close();
        workers.submit(() -> assertClosedInstallation(host)).get(5, TimeUnit.SECONDS);
        assertRejectedServiceCalls(retained, "closed");
        assertTrue(HandlerList.getRegisteredListeners(plugin).isEmpty());
        assertNoSideEffects();
    }

    @Test void pluginDisableRejectsWorkerInstallationAndPreviouslyObtainedServicesOnMainThread() throws Exception {
        BukkitScriptHost host = host();
        Map<String, Executable> retained = workers.submit(() -> serviceCalls(context(host)))
                .get(5, TimeUnit.SECONDS);
        FakeBukkitScriptServer.fire(new PluginDisableEvent(plugin));
        workers.submit(() -> assertClosedInstallation(host)).get(5, TimeUnit.SECONDS);
        assertRejectedServiceCalls(retained, "closed");
        assertTrue(HandlerList.getRegisteredListeners(plugin).isEmpty());
        assertNoSideEffects();
    }

    @Test void constructionAndCloseStillRequireTheMainThread() throws Exception {
        BukkitScriptHost host = host();
        workers.submit(() -> {
            IllegalStateException construction = assertThrows(IllegalStateException.class,
                    () -> BukkitScriptHost.builder(plugin, scope).build());
            assertTrue(construction.getMessage().contains("main thread"));
            IllegalStateException closing = assertThrows(IllegalStateException.class, host::close);
            assertTrue(closing.getMessage().contains("main thread"));
        }).get(5, TimeUnit.SECONDS);
        assertComplete(context(host), context(host));
        assertEquals(2, HandlerList.getRegisteredListeners(plugin).size());
        assertNoSideEffects();
    }

    private BukkitScriptHost host() {
        return BukkitScriptHost.builder(plugin, scope)
                .scoreboard((target, lines) -> scoreboardCallbacks.incrementAndGet())
                .javascript(() -> {
                    javascriptFactories.incrementAndGet();
                    return (source, bindings) -> { javascriptEvaluations.incrementAndGet(); return source; };
                }).build();
    }

    private ScopedScriptRuntime runtime() {
        return new ScopedScriptRuntime(scope, new StatementRegistry(), null,
                task -> { throw new AssertionError("Pure expressions must finish on the calling thread"); }, true);
    }

    private ScriptContext context(BukkitScriptHost host) { return host.apply(ScriptContext.builder()).build(); }

    private static void assertComplete(ScriptContext reference, ScriptContext context) {
        for (Class<?> type : SERVICE_TYPES) {
            assertSame(reference.requireService(type), context.requireService(type), type.getName());
        }
    }

    private static void assertUninstalled(ScriptContext context) {
        for (Class<?> type : SERVICE_TYPES) assertFalse(context.service(type).isPresent(), type.getName());
    }

    private static void assertClosedInstallation(BukkitScriptHost host) {
        ScriptContext.Builder builder = ScriptContext.builder().variable("caller", "preserved");
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> host.apply(builder));
        assertTrue(failure.getMessage().contains("closed"), failure.getMessage());
        ScriptContext context = builder.build();
        assertUninstalled(context);
        assertEquals("preserved", context.variableOrNull("caller"));
    }

    private void assertRejectedServiceCalls(ScriptContext context, String expectedMessage) {
        assertRejectedServiceCalls(serviceCalls(context), expectedMessage);
    }

    private static void assertRejectedServiceCalls(Map<String, Executable> calls, String expectedMessage) {
        for (Map.Entry<String, Executable> call : calls.entrySet()) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, call.getValue(), call.getKey());
            assertTrue(failure.getMessage().contains(expectedMessage), call.getKey() + ": " + failure.getMessage());
        }
    }

    private Map<String, Executable> serviceCalls(ScriptContext context) {
        Map<String, Executable> calls = new LinkedHashMap<String, Executable>();
        MessageSink messages = context.requireService(MessageSink.class);
        CommandSink commands = context.requireService(CommandSink.class);
        calls.put("message", () -> messages.send(player, "forbidden"));
        calls.put("command", () -> commands.dispatch(player, "say forbidden"));
        calls.put("operator command", () -> commands.dispatchAsOperator(player, "say forbidden"));
        ScriptSenderQuery sender = context.requireService(ScriptSenderQuery.class);
        calls.put("sender type", () -> sender.isPlayer(player));
        calls.put("sender name", () -> sender.name(player));
        calls.put("sender online", () -> sender.isOnline(player));
        PlayerQuery query = context.requireService(PlayerQuery.class);
        calls.put("player property", () -> query.property(player, "level"));
        calls.put("player permission", () -> query.hasPermission(player, "forbidden"));
        calls.put("player write", () -> query.write(player, "level", PlayerQuery.Method.MODIFY, Integer.valueOf(3)));
        DelayScheduler delays = context.requireService(DelayScheduler.class);
        ScriptLogger logger = context.requireService(ScriptLogger.class);
        calls.put("delay", () -> delays.delay(Duration.ofMillis(50)));
        calls.put("logger", () -> logger.log(Level.INFO, "forbidden"));
        ScriptPropertyAccess properties = context.requireService(ScriptPropertyAccess.class);
        calls.put("property read", () -> properties.read(itemMeta, "name"));
        calls.put("property write", () -> properties.write(itemMeta, "name", "forbidden"));
        JavaScriptEvaluator javascript = context.requireService(JavaScriptEvaluator.class);
        calls.put("javascript", () -> javascript.eval("1 + 1", Collections.<String, Object>emptyMap()));
        ScriptPlatform platform = context.requireService(ScriptPlatform.class);
        calls.put("online players", platform::onlinePlayerNames);
        calls.put("broadcast", () -> platform.broadcast("forbidden"));
        calls.put("console", platform::console);
        calls.put("player lookup", () -> platform.player("Alex"));
        calls.put("action bar", () -> platform.actionBar(player, "forbidden"));
        calls.put("title", () -> platform.title(player, "forbidden", "subtitle", 1, 1, 1));
        calls.put("sound", () -> platform.playSound(player, "resource:forbidden", 1f, 1f));
        calls.put("stop sound", () -> platform.stopSound(player, "resource:forbidden"));
        calls.put("location", () -> platform.location("world", 0, 0, 0, 0, 0));
        calls.put("material", () -> platform.material("STONE"));
        calls.put("item", () -> platform.itemStack("STONE"));
        calls.put("scoreboard callback", () -> platform.scoreboard(player, Arrays.asList("Title", "line")));
        return calls;
    }

    private void assertNoSideEffects() {
        assertTrue(messages.isEmpty(), "A Bukkit message was sent");
        assertEquals(0, offThreadCalls.get(), "A worker reached a Bukkit API");
        assertEquals(0, scoreboardCallbacks.get(), "Scoreboard callback ran");
        assertEquals(0, javascriptFactories.get(), "JavaScript was initialized eagerly");
        assertEquals(0, javascriptEvaluations.get(), "JavaScript was evaluated");
        assertEquals(0, server.pendingTasks(), "A Bukkit task was scheduled");
        assertEquals(0, server.cancelledTasks(), "A Bukkit task was cancelled");
    }

    private <T> T observed(Class<T> type, T delegate) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("equals".equals(method.getName())) return Boolean.valueOf(proxy == arguments[0]);
                        if ("hashCode".equals(method.getName())) return Integer.valueOf(System.identityHashCode(proxy));
                        return "Observed " + type.getSimpleName();
                    }
                    if (Thread.currentThread() != primary && !"isPrimaryThread".equals(method.getName())) {
                        offThreadCalls.incrementAndGet();
                        throw new AssertionError("Worker reached " + type.getSimpleName() + "." + method.getName());
                    }
                    if (type == Player.class && "sendMessage".equals(method.getName())) {
                        messages.add((String) arguments[0]);
                    }
                    try { return method.invoke(delegate, arguments); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                }));
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for context workers"); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Context worker interrupted", failure);
        }
    }
}
