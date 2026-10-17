package me.kzheart.klib.command;

import me.kzheart.klib.lang.RichTextSegment;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandPermissionFilterTest {
    @Test
    void filtersProtectedCommandsFromHelpAndCompletion() {
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.literal("public", child -> child.executes(context -> {
        }));
        spec.literal("admin", child -> child
                .permission("demo.admin")
                .executes(context -> {
                }));
        CommandDispatcher dispatcher = new CommandDispatcher(spec);
        TestSenders.SenderFixture sender = TestSenders.console();

        List<String> completion = dispatcher.complete(sender.sender(), new String[]{""});
        HelpPage help = dispatcher.renderHelp(sender.sender(), 1, 10);

        assertEquals(1, completion.size());
        assertEquals("public", completion.get(0));
        assertTrue(help.content().plainText().contains("/demo public"));
        assertFalse(help.content().plainText().contains("/demo admin"));
        for (RichTextSegment segment : help.content().segments()) {
            assertFalse(segment.text().contains("admin"));
        }
    }

    @Test
    void rejectsExactProtectedCommandWithoutExecutingIt() {
        boolean[] executed = {false};
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.literal("admin", child -> child
                .permission("demo.admin")
                .executes(context -> executed[0] = true));
        CommandDispatcher dispatcher = new CommandDispatcher(spec);

        CommandResult result = dispatcher.execute(
                TestSenders.console().sender(),
                new String[]{"admin"});

        assertEquals(CommandResult.Status.NO_PERMISSION, result.status());
        assertFalse(executed[0]);
    }

    @Test
    void inaccessibleLiteralDoesNotShadowSiblingArgument() {
        String[] captured = {null};
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.literal("admin", child -> child
                .permission("demo.admin")
                .executes(context -> {
                }));
        Arg<String> name = Arguments.choice("name", "admin", "guest");
        Arg<String> action = Arguments.choice("action", "kick", "ban");
        spec.argument(name, child -> {
            child.executes(context -> captured[0] = context.get(name));
            child.argument(action, deeper -> deeper.executes(context -> {
            }));
        });
        CommandDispatcher dispatcher = new CommandDispatcher(spec);
        TestSenders.SenderFixture sender = TestSenders.console();

        CommandResult result = dispatcher.execute(sender.sender(), new String[]{"admin"});

        assertEquals(CommandResult.Status.SUCCESS, result.status());
        assertEquals("admin", captured[0]);
        // 补全同理：literal 无权限时仍能穿透到同级 argument 的子节点建议
        List<String> completion = dispatcher.complete(
                sender.sender(), new String[]{"admin", ""});
        assertTrue(completion.contains("kick"));
    }

    @Test
    void rejectsPlayerOnlyCommandForConsole() {
        boolean[] executed = {false};
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.literal("play", child -> child.playerOnly().executes(context -> executed[0] = true));
        CommandDispatcher dispatcher = new CommandDispatcher(spec);

        CommandResult result = dispatcher.execute(
                TestSenders.console().sender(),
                new String[]{"play"});

        assertEquals(CommandResult.Status.PLAYER_ONLY, result.status());
        assertFalse(executed[0]);
    }

    @Test
    void protectedArgumentParserIsNeverCalledDuringExecuteOrCompletion() {
        AtomicInteger parses = new AtomicInteger();
        Arg<String> secret = Arguments.custom("secret", input -> {
            parses.incrementAndGet();
            return input;
        }, null);
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.argument(secret, child -> child
                .permission("demo.secret")
                .executes(context -> {
                }));
        CommandDispatcher dispatcher = new CommandDispatcher(spec);
        CommandSender sender = TestSenders.console().sender();

        dispatcher.execute(sender, new String[]{"value"});
        dispatcher.complete(sender, new String[]{"value", ""});

        assertEquals(0, parses.get());
    }

    @Test
    void playerOnlyArgumentParserIsNeverCalledForConsole() {
        AtomicInteger parses = new AtomicInteger();
        Arg<String> playerValue = Arguments.custom("value", input -> {
            parses.incrementAndGet();
            return input;
        }, null);
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.argument(playerValue, child -> child
                .playerOnly()
                .executes(context -> {
                }));
        CommandDispatcher dispatcher = new CommandDispatcher(spec);
        CommandSender console = TestSenders.console().sender();

        dispatcher.execute(console, new String[]{"value"});
        dispatcher.complete(console, new String[]{"value", ""});

        assertEquals(0, parses.get());
    }
}
