package me.kzheart.klib.command;

import me.kzheart.klib.command.api.CommandCapability;
import me.kzheart.klib.lang.RichText;
import me.kzheart.klib.lang.MessagePipeline;
import me.kzheart.klib.scope.ScopeImpl;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandModuleAndBuiltinsTest {
    @Test
    void moduleInstallsCapabilityAndOwnedRegistrations() {
        ScopeImpl scope = new ScopeImpl("test");
        AtomicInteger active = new AtomicInteger();
        CommandBridge bridge = (name, spec, dispatcher) -> {
            active.incrementAndGet();
            return active::decrementAndGet;
        };

        CommandCapability installed = CommandModule.install(scope, bridge);
        scope.command("demo", spec -> spec.executes(context -> {
        }));

        assertSame(installed, scope.requireCapability(CommandCapability.class));
        assertEquals(1, active.get());
        scope.close();
        assertEquals(0, active.get());
    }

    @Test
    void builtinsUseTypedTreeCallbacksPermissionsAndMessageKeys() {
        AtomicInteger reloads = new AtomicInteger();
        AtomicBoolean debug = new AtomicBoolean();
        RecordingMessages messages = new RecordingMessages();
        List<RichText> sent = new ArrayList<RichText>();
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        CommandBuiltins.create()
                .help(true, "demo.help")
                .reload("demo.reload", reloads::incrementAndGet)
                .debug("demo.debug", debug::get, debug::set)
                .install(spec);
        CommandDispatcher dispatcher = new CommandDispatcher(
                spec,
                BukkitPlayerResolver.INSTANCE,
                (sender, text) -> sent.add(text),
                messages);

        CommandResult denied = dispatcher.execute(
                TestSenders.console().sender(),
                new String[]{"reload"});
        assertEquals(CommandResult.Status.NO_PERMISSION, denied.status());
        assertEquals(CommandMessageKeys.NO_PERMISSION, messages.lastKey());
        assertEquals(0, reloads.get());

        CommandSender allowed = TestSenders.console(
                "demo.help",
                "demo.reload",
                "demo.debug").sender();
        dispatcher.execute(allowed, new String[]{"reload"});
        assertEquals(1, reloads.get());
        assertEquals(CommandMessageKeys.BUILTIN_RELOAD_SUCCESS, messages.lastKey());

        dispatcher.execute(allowed, new String[]{"debug"});
        assertTrue(debug.get());
        assertEquals(CommandMessageKeys.BUILTIN_DEBUG_ENABLED, messages.lastKey());
        dispatcher.execute(allowed, new String[]{"debug"});
        assertFalse(debug.get());
        assertEquals(CommandMessageKeys.BUILTIN_DEBUG_DISABLED, messages.lastKey());

        dispatcher.execute(allowed, new String[]{"help", "1"});
        assertTrue(messages.keys().contains(CommandMessageKeys.HELP_HEADER));
        assertTrue(messages.keys().contains(CommandMessageKeys.BUILTIN_HELP_DESCRIPTION));
        assertFalse(sent.isEmpty());
    }

    @Test
    void asyncReloadAcknowledgesOnlyAfterListenersComplete() {
        CompletableFuture<Void> reload = new CompletableFuture<Void>();
        RecordingMessages messages = new RecordingMessages();
        List<RichText> sent = new ArrayList<RichText>();
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        CommandBuiltins.standardAsync(
                "demo.admin",
                () -> reload,
                () -> false,
                ignored -> {
                }).install(spec);
        CommandDispatcher dispatcher = new CommandDispatcher(
                spec,
                BukkitPlayerResolver.INSTANCE,
                (sender, text) -> sent.add(text),
                messages);

        CommandResult result = dispatcher.execute(
                TestSenders.console("demo.admin").sender(),
                new String[]{"reload"});

        assertEquals(CommandResult.Status.SUCCESS, result.status());
        assertTrue(sent.isEmpty());
        reload.complete(null);
        assertEquals(1, sent.size());
        assertEquals(CommandMessageKeys.BUILTIN_RELOAD_SUCCESS, messages.lastKey());
    }

    @Test
    void asyncReloadFailureUsesLocalizedInternalError() {
        CompletableFuture<Void> reload = new CompletableFuture<Void>();
        RecordingMessages messages = new RecordingMessages();
        List<RichText> sent = new ArrayList<RichText>();
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        CommandBuiltins.standardAsync(
                "demo.admin",
                () -> reload,
                () -> false,
                ignored -> {
                }).install(spec);
        CommandDispatcher dispatcher = new CommandDispatcher(
                spec,
                BukkitPlayerResolver.INSTANCE,
                (sender, text) -> sent.add(text),
                messages);

        dispatcher.execute(TestSenders.console("demo.admin").sender(),
                new String[]{"reload"});
        reload.completeExceptionally(new IllegalStateException("broken"));

        assertEquals(1, sent.size());
        assertEquals(CommandMessageKeys.INTERNAL_ERROR, messages.lastKey());
    }

    @Test
    void messagePipelineAdapterResolvesCatalogKeysWithoutDeliveringTwice() {
        MessagePipelineCommandMessages messages = new MessagePipelineCommandMessages(
                key -> java.util.Optional.of("{prefix}value={value}"),
                "prefix ",
                null);

        RichText result = messages.resolve(
                TestSenders.console().sender(),
                "custom.key",
                MessagePlaceholders.of("value", "ok"));

        assertEquals("prefix value=ok", result.plainText());
    }

    @Test
    void moduleCanUseAnExistingLanguagePipelineAsItsStandardMessages() {
        ScopeImpl scope = new ScopeImpl("test");
        CommandDispatcher[] registered = {null};
        CommandBridge bridge = (name, spec, dispatcher) -> {
            registered[0] = dispatcher;
            return () -> {
            };
        };
        AtomicInteger routed = new AtomicInteger();
        MessagePipeline pipeline = new MessagePipeline(
                key -> java.util.Optional.of("localized:" + key + " {value}"),
                "",
                null,
                (recipient, message) -> routed.incrementAndGet());

        CommandCapability capability = CommandModule.install(scope, bridge, pipeline);
        capability.register(scope, "demo", spec -> spec
                .permission("demo.use")
                .executes(context -> {
                }));

        CommandResult result = registered[0].execute(
                TestSenders.console().sender(), new String[0]);

        assertEquals(
                "localized:" + CommandMessageKeys.NO_PERMISSION + " {value}",
                result.message().plainText());
        assertEquals(0, routed.get());
        scope.close();
    }

    @Test
    void argumentFailuresResolveThroughInjectedMessageKey() {
        RecordingMessages messages = new RecordingMessages();
        Arg<Integer> count = Arguments.integer("count");
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.argument(count, child -> child.executes(context -> {
        }));
        CommandDispatcher dispatcher = new CommandDispatcher(
                spec,
                BukkitPlayerResolver.INSTANCE,
                (sender, text) -> {
                },
                messages);

        dispatcher.execute(TestSenders.console().sender(), new String[]{"bad"});

        assertEquals(CommandMessageKeys.ARG_INTEGER, messages.lastKey());
    }

    @Test
    @SuppressWarnings("deprecation")
    void standardBuiltinsRequirePermissionUnlessExplicitlyUnrestricted() {
        CommandSpecImpl legacy = CommandSpecImpl.command("demo");
        CommandBuiltins.standard(() -> {
        }, () -> false, value -> {
        }).install(legacy);
        CommandDispatcher legacyDispatcher = new CommandDispatcher(legacy);
        // 旧签名默认拒绝：无权限的 sender 不能执行 reload
        assertEquals(
                CommandResult.Status.NO_PERMISSION,
                legacyDispatcher.execute(
                        TestSenders.console().sender(),
                        new String[]{"reload"}).status());

        AtomicInteger reloads = new AtomicInteger();
        CommandSpecImpl explicit = CommandSpecImpl.command("demo");
        CommandBuiltins.standard(
                "demo.admin",
                reloads::incrementAndGet,
                () -> false,
                value -> {
                }).install(explicit);
        CommandDispatcher explicitDispatcher = new CommandDispatcher(explicit);
        assertEquals(
                CommandResult.Status.NO_PERMISSION,
                explicitDispatcher.execute(
                        TestSenders.console().sender(),
                        new String[]{"reload"}).status());
        assertEquals(
                CommandResult.Status.SUCCESS,
                explicitDispatcher.execute(
                        TestSenders.console("demo.admin").sender(),
                        new String[]{"reload"}).status());
        assertEquals(1, reloads.get());

        CommandSpecImpl open = CommandSpecImpl.command("demo");
        CommandBuiltins.standard(
                CommandBuiltins.PERMISSION_NONE,
                () -> {
                },
                () -> false,
                value -> {
                }).install(open);
        assertEquals(
                CommandResult.Status.SUCCESS,
                new CommandDispatcher(open).execute(
                        TestSenders.console().sender(),
                        new String[]{"reload"}).status());
    }

    @Test
    void handlerFailureIsCaughtLocalizedAndReturnsFailed() {
        RecordingMessages messages = new RecordingMessages();
        List<RichText> sent = new ArrayList<RichText>();
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.executes(context -> {
            throw new IllegalStateException("boom");
        });
        CommandDispatcher dispatcher = new CommandDispatcher(
                spec,
                BukkitPlayerResolver.INSTANCE,
                (sender, text) -> sent.add(text),
                messages);

        CommandResult result = dispatcher.execute(
                TestSenders.console().sender(),
                new String[0]);

        assertEquals(CommandResult.Status.FAILED, result.status());
        assertEquals(CommandMessageKeys.INTERNAL_ERROR, messages.lastKey());
        assertFalse(sent.isEmpty());
    }

    @Test
    void unknownArgumentResolvesWithArgumentPlaceholder() {
        List<Map<String, ?>> placeholders = new ArrayList<Map<String, ?>>();
        CommandMessages messages = (sender, key, values) -> {
            if (CommandMessageKeys.UNKNOWN_ARGUMENT.equals(key)) {
                placeholders.add(values);
            }
            return RichText.plain(key);
        };
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.literal("known", child -> child.executes(context -> {
        }));
        CommandDispatcher dispatcher = new CommandDispatcher(
                spec,
                BukkitPlayerResolver.INSTANCE,
                (sender, text) -> {
                },
                messages);

        dispatcher.execute(TestSenders.console().sender(), new String[]{"zzz"});

        assertEquals(1, placeholders.size());
        assertEquals("zzz", placeholders.get(0).get("argument"));
    }

    private static final class RecordingMessages implements CommandMessages {
        private final List<String> keys = new ArrayList<String>();

        @Override
        public RichText resolve(
                CommandSender sender,
                String key,
                Map<String, ?> placeholders
        ) {
            keys.add(key);
            return RichText.plain(key);
        }

        private String lastKey() {
            return keys.get(keys.size() - 1);
        }

        private List<String> keys() {
            return keys;
        }
    }
}
