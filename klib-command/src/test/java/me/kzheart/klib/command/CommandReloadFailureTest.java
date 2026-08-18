package me.kzheart.klib.command;

import me.kzheart.klib.config.ConfigException;
import me.kzheart.klib.lang.RichText;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandReloadFailureTest {
    @Test
    void syncReloadFailureEchoesConfigReason() {
        RecordingMessages messages = new RecordingMessages();
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        CommandBuiltins.standard(
                CommandBuiltins.PERMISSION_NONE,
                () -> {
                    throw new ConfigException("config.yml:limits.max: 需要整数");
                },
                () -> false,
                ignored -> {
                }).install(spec);
        CommandDispatcher dispatcher = dispatcher(spec, messages);

        CommandResult result = dispatcher.execute(
                TestSenders.console().sender(), new String[]{"reload"});

        assertEquals(CommandResult.Status.FAILED, result.status());
        assertEquals(CommandMessageKeys.BUILTIN_RELOAD_FAILURE, messages.lastKey());
        assertEquals("config.yml:limits.max: 需要整数", messages.lastPlaceholders().get("reason"));
    }

    @Test
    void asyncReloadFailureEchoesWrappedConfigReason() {
        CompletableFuture<Void> reload = new CompletableFuture<Void>();
        RecordingMessages messages = new RecordingMessages();
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        CommandBuiltins.standardAsync(
                CommandBuiltins.PERMISSION_NONE,
                () -> reload,
                () -> false,
                ignored -> {
                }).install(spec);
        CommandDispatcher dispatcher = dispatcher(spec, messages);

        dispatcher.execute(TestSenders.console().sender(), new String[]{"reload"});
        reload.completeExceptionally(
                new IllegalStateException("wrapper", new ConfigException("a.yml:x: 坏了")));

        assertEquals(CommandMessageKeys.BUILTIN_RELOAD_FAILURE, messages.lastKey());
        assertEquals("a.yml:x: 坏了", messages.lastPlaceholders().get("reason"));
    }

    @Test
    void nonConfigFailuresKeepTheGenericInternalError() {
        RecordingMessages messages = new RecordingMessages();
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        CommandBuiltins.standard(
                CommandBuiltins.PERMISSION_NONE,
                () -> {
                    throw new IllegalStateException("broken");
                },
                () -> false,
                ignored -> {
                }).install(spec);
        CommandDispatcher dispatcher = dispatcher(spec, messages);

        CommandResult result = dispatcher.execute(
                TestSenders.console().sender(), new String[]{"reload"});

        assertEquals(CommandResult.Status.FAILED, result.status());
        assertEquals(CommandMessageKeys.INTERNAL_ERROR, messages.lastKey());
    }

    @Test
    void reasonIsStrippedOfLegacyColorCodesAndTruncated() {
        RecordingMessages messages = new RecordingMessages();
        StringBuilder detail = new StringBuilder("§ca.yml:x: ");
        for (int index = 0; index < 300; index++) {
            detail.append('z');
        }
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        CommandBuiltins.standard(
                CommandBuiltins.PERMISSION_NONE,
                () -> {
                    throw new ConfigException(detail.toString());
                },
                () -> false,
                ignored -> {
                }).install(spec);
        CommandDispatcher dispatcher = dispatcher(spec, messages);

        dispatcher.execute(TestSenders.console().sender(), new String[]{"reload"});

        String reason = String.valueOf(messages.lastPlaceholders().get("reason"));
        assertFalse(reason.contains("§"));
        assertTrue(reason.length() <= 200);
        assertTrue(reason.endsWith("…"));
    }

    private static CommandDispatcher dispatcher(
            CommandSpecImpl spec,
            CommandMessages messages
    ) {
        return new CommandDispatcher(
                spec,
                BukkitPlayerResolver.INSTANCE,
                (sender, text) -> {
                },
                messages);
    }

    private static final class RecordingMessages implements CommandMessages {
        private final List<String> keys = new ArrayList<String>();
        private final List<Map<String, ?>> placeholders = new ArrayList<Map<String, ?>>();

        @Override
        public RichText resolve(
                CommandSender sender,
                String key,
                Map<String, ?> values
        ) {
            keys.add(key);
            placeholders.add(new LinkedHashMap<String, Object>(values));
            return RichText.plain(key);
        }

        private String lastKey() {
            return keys.get(keys.size() - 1);
        }

        private Map<String, ?> lastPlaceholders() {
            return placeholders.get(placeholders.size() - 1);
        }
    }
}
