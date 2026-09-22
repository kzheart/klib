package me.kzheart.klib.command;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import me.kzheart.klib.command.annotation.*;
import me.kzheart.klib.command.api.*;
import me.kzheart.klib.component.KContext;
import me.kzheart.klib.scope.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnnotatedCommandsTest {
    static class Fixture implements AutoCloseable {
        final ScopeImpl scope = new ScopeImpl("commands");
        final Map<String, CommandDispatcher> registered = new LinkedHashMap<String, CommandDispatcher>();
        Fixture() {
            scope.registerCapability(CommandCapability.class, new CommandCapabilityImpl((name, spec, dispatcher) -> {
                registered.put(name, dispatcher); return () -> registered.remove(name);
            }));
        }
        void register(Object... objects) { new KContext(scope).commands().register(objects); }
        CommandDispatcher dispatcher() { return registered.get("demo"); }
        @Override public void close() { scope.close(); }
    }
    @Command(value = "demo", aliases = {"d"})
    @Permission("use") @Check("ready")
    public static class Routes {
        String value;
        boolean ready = true;
        int checks;
        @CheckHandler("ready") public void ready() {
            checks++;
            if (!ready) throw new CommandRejectedException("not ready");
        }
        @Route({"", "ui"}) public void ui(Player player) { value = "ui"; }
        @Route("search <query>") public void search(Player player, @Param("query") @Greedy String query) { value = query; }
        @Route("select <dungeon> <difficulty>")
        public void select(Player player, @Param("dungeon") String dungeon,
                @Param("difficulty") @Suggest("difficulty") String difficulty) { value = dungeon + ":" + difficulty; }
        @Suggestions("difficulty") public List<String> difficulties(SuggestionContext context) {
            return Arrays.asList(context.get("dungeon", String.class) + "-easy", "other");
        }
        @Route("number <number>") public void number(CommandSender sender, @Param("number") int number) { value = "n" + number; }
        @Route("resolve <id>") public void resolve(CommandSender sender, @Param("id") UUID id) { value = id.toString(); }
    }
    @Command("demo") @Permission("admin")
    public static class Admin {
        int calls;
        @Route("reload") public void reload(CommandSender sender) { calls++; }
    }
    @Test void clientTreeUsesTheSameAccessRulesAsServerCompletion() {
        ScopeImpl scope = new ScopeImpl("brigadier");
        List<BrigadierTree> trees = new ArrayList<BrigadierTree>();
        scope.registerCapability(CommandCapability.class, new CommandCapabilityImpl((name, spec, dispatcher) -> {
            trees.add(BrigadierTree.from(spec)); return () -> { };
        }));
        new KContext(scope).commands().register(new Routes(), new Admin());
        CommandSender admin = TestSenders.console("admin").sender();
        CommandSender player = TestSenders.player("p", "use").sender();
        for (BrigadierTree tree : trees) {
            assertTrue(tree.accessible(admin));
            for (BrigadierTree child : tree.children()) {
                if (child.token().equals("reload")) { assertTrue(child.accessible(admin)); assertFalse(child.accessible(player)); }
                if (child.token().equals("ui")) { assertFalse(child.accessible(admin)); assertTrue(child.accessible(player)); }
            }
        }
        scope.close();
    }

    @Test void callDeliversCompletedSuccessAndFailureOnBoundExecutorAndSuppressesAfterClose() {
        java.util.concurrent.ScheduledExecutorService timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        ScopeImpl scope = new ScopeImpl("call");
        Queue<Runnable> queued = new ArrayDeque<Runnable>();
        try {
            scope.registerCapability(me.kzheart.klib.scheduler.SchedulerFactory.class,
                    owner -> new me.kzheart.klib.scheduler.ExecutorScheduler(owner, timer, pool, queued::add));
            TestSenders.SenderFixture sender = TestSenders.console();
            CommandCall call = new CommandCall(new CommandContextImpl(sender.sender(), "test", Collections.emptyMap()), scope);
            call.await(java.util.concurrent.CompletableFuture.completedFuture("ready"), call::reply, error -> fail(error));
            assertTrue(sender.messages().isEmpty()); queued.remove().run(); assertEquals(Collections.singletonList("ready"), sender.messages());
            java.util.concurrent.CompletableFuture<String> failed = new java.util.concurrent.CompletableFuture<String>();
            failed.completeExceptionally(new java.util.concurrent.CompletionException(new IllegalArgumentException("bad")));
            call.await(failed, value -> fail("must not succeed"), error -> call.reply(error.getMessage()));
            queued.remove().run(); assertEquals(Arrays.asList("ready", "bad"), sender.messages());
            call.await(java.util.concurrent.CompletableFuture.completedFuture("late"), call::reply, error -> fail(error));
            scope.close(); queued.remove().run(); assertEquals(Arrays.asList("ready", "bad"), sender.messages());
        } finally { scope.close(); timer.shutdownNow(); pool.shutdownNow(); }
    }

    @Test void flatRoutesReuseLiteralsAndAppendTypedArguments() {
        Arg<Integer> amount = Arguments.integer("amount");
        AtomicInteger value = new AtomicInteger();
        CommandSpecImpl spec = CommandSpecImpl.command("flat");
        spec.route("admin set").argument(amount).executes(c -> value.set(c.get(amount)));
        spec.route("admin reset").executes(c -> value.set(0));
        CommandDispatcher dispatcher = new CommandDispatcher(spec);
        dispatcher.execute(TestSenders.console().sender(), new String[]{"admin", "set", "7"});
        assertEquals(7, value.get());
        dispatcher.execute(TestSenders.console().sender(), new String[]{"admin", "reset"});
        assertEquals(0, value.get());
    }

    @Test void injectsSenderParsesTypesGreedyAndAliases() {
        try (Fixture f = new Fixture()) {
            Routes routes = new Routes(); f.register(routes);
            CommandSender player = TestSenders.player("p", "use").sender();
            assertEquals(CommandResult.Status.SUCCESS, f.dispatcher().execute(player, new String[]{"search", "hello", "world"}).status());
            assertEquals("hello world", routes.value);
            f.registered.get("d").execute(player, new String[]{"number", "42"});
            assertEquals("n42", routes.value);
            UUID id = UUID.randomUUID();
            f.dispatcher().execute(player, new String[]{"resolve", id.toString()});
            assertEquals(id.toString(), routes.value);
            assertEquals(CommandResult.Status.INVALID_ARGUMENT, f.dispatcher().execute(player, new String[]{"number", "bad"}).status());
            assertEquals(CommandResult.Status.INVALID_ARGUMENT, f.dispatcher().execute(player, new String[]{"resolve", "bad"}).status());
        }
    }
    @Test void playerAndAdminPermissionsRemainIndependentOnSharedRoot() {
        try (Fixture f = new Fixture()) {
            Routes routes = new Routes(); Admin admin = new Admin(); f.register(routes, admin);
            CommandSender console = TestSenders.console("admin").sender();
            assertEquals(CommandResult.Status.SUCCESS, f.dispatcher().execute(console, new String[]{"reload"}).status());
            assertEquals(1, admin.calls);
            f.registered.get("d").execute(console, new String[]{"reload"});
            assertEquals(2, admin.calls);
            assertNotEquals(CommandResult.Status.SUCCESS, f.dispatcher().execute(console, new String[]{"ui"}).status());
            assertEquals(Collections.singletonList("reload"), f.dispatcher().complete(console, new String[]{""}));
            assertFalse(f.dispatcher().renderHelp(console, 1, 20).content().plainText().contains("select"));
            CommandSender player = TestSenders.player("p", "use").sender();
            assertFalse(f.dispatcher().complete(player, new String[]{""}).contains("reload"));
            assertNotEquals(CommandResult.Status.SUCCESS, f.dispatcher().execute(player, new String[]{"reload"}).status());
        }
    }
    @Test void completionSeesPreviousArgumentsAndNeverRunsBusinessChecks() {
        try (Fixture f = new Fixture()) {
            Routes routes = new Routes(); f.register(routes);
            assertEquals(Collections.singletonList("castle-easy"), f.dispatcher().complete(
                    TestSenders.player("p", "use").sender(), new String[]{"select", "castle", "c"}));
            assertEquals(0, routes.checks);
            routes.ready = false;
            TestSenders.SenderFixture player = TestSenders.player("p", "use");
            assertEquals(CommandResult.Status.FAILED, f.dispatcher().execute(player.sender(), new String[]{"ui"}).status());
            assertTrue(player.messages().contains("not ready"));
            assertNull(routes.value);
        }
    }
    @Test void disposalAndRebuildDoNotDuplicateRegistrations() {
        AtomicInteger active = new AtomicInteger();
        CommandCapabilityImpl capability = new CommandCapabilityImpl((name, spec, dispatcher) -> {
            active.incrementAndGet(); return active::decrementAndGet;
        });
        ScopeImpl root = ScopeImpl.create("root", s -> {
            s.registerCapability(CommandCapability.class, capability);
            new KContext(s).commands().register(new Routes());
        });
        assertEquals(2, active.get()); root.rebuild(); assertEquals(2, active.get());
        root.close(); assertEquals(0, active.get());
    }
    @Command("demo") public static class Invalid {
        @Route("bad <missing>") public void bad(CommandSender sender) { }
    }
    @Command("demo") public static class PrivateRoute {
        @Route("bad") private void bad() { }
    }
    @Command("demo") public static class MissingSuggestion {
        @Route("bad <x>") public void bad(@Param("x") @Suggest("absent") String x) { }
    }
    @Command("demo") public static class NonFinalGreedy {
        @Route("bad <x> later") public void bad(@Param("x") @Greedy String x) { }
    }
    @Test void invalidDeclarationsNeverPartiallyRegister() {
        for (Object invalid : Arrays.asList(new Invalid(), new PrivateRoute(), new MissingSuggestion(), new NonFinalGreedy())) {
            try (Fixture f = new Fixture()) {
                assertThrows(IllegalArgumentException.class, () -> f.register(new Routes(), invalid));
                assertTrue(f.registered.isEmpty());
            }
        }
    }
    @Test void duplicateRoutesFailBeforeRegistration() {
        try (Fixture f = new Fixture()) {
            assertThrows(IllegalArgumentException.class, () -> f.register(new Routes(), new Routes()));
            assertTrue(f.registered.isEmpty());
        }
    }
    @Test void bridgeFailureRollsBackAlreadyRegisteredAliases() {
        AtomicInteger active = new AtomicInteger();
        ScopeImpl scope = new ScopeImpl("root");
        scope.registerCapability(CommandCapability.class, new CommandCapabilityImpl((name, spec, dispatcher) -> {
            if (name.equals("d")) throw new IllegalStateException("bridge failed");
            active.incrementAndGet(); return active::decrementAndGet;
        }));
        assertThrows(IllegalStateException.class, () -> new KContext(scope).commands().register(new Routes()));
        assertEquals(0, active.get()); scope.close();
    }
    @Test void contextualParserCanReadSenderAndEarlierValues() {
        Arg<String> dungeon = Arguments.string("dungeon");
        Arg<String> mode = Arguments.contextual("mode", (input, context) -> {
            if (!input.startsWith(context.get("dungeon", String.class))) throw new IllegalArgumentException();
            return input;
        }, context -> Collections.singletonList(context.get("dungeon", String.class) + "-easy"));
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.argument(dungeon, d -> d.argument(mode, m -> m.executes(c -> { })));
        CommandDispatcher dispatcher = new CommandDispatcher(spec);
        CommandSender sender = TestSenders.console().sender();
        assertEquals(CommandResult.Status.SUCCESS, dispatcher.execute(sender, new String[]{"a", "a-easy"}).status());
        assertEquals(CommandResult.Status.INVALID_ARGUMENT, dispatcher.execute(sender, new String[]{"a", "b-easy"}).status());
        assertEquals(Collections.singletonList("a-easy"), dispatcher.complete(sender, new String[]{"a", ""}));
    }
}
