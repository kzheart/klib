package me.kzheart.klib.command;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandArgumentsAndSuggestionsTest {
    private enum Mode {
        FAST,
        SAFE
    }

    @Test
    void diagnosticSnapshotCountsInvocationsWithoutCapturingArguments() {
        CommandSpecImpl spec = CommandSpecImpl.command("diagnostic");
        spec.executes(context -> { });
        CommandDispatcher dispatcher = new CommandDispatcher(spec);

        dispatcher.execute(TestSenders.console().sender(), new String[0]);
        java.util.Map<String, ?> snapshot = dispatcher.diagnosticSnapshot();

        assertEquals("diagnostic", snapshot.get("name"));
        assertEquals(1L, snapshot.get("invocations"));
        assertFalse(snapshot.containsKey("arguments"));
    }

    @Test
    void parsesAllTypedArgumentsAndGreedyText() {
        Arg<Integer> count = Arguments.integer("count", 1, 10);
        Arg<BigDecimal> price = Arguments.decimal(
                "price", BigDecimal.ZERO, new BigDecimal("100"));
        Arg<Boolean> enabled = Arguments.bool("enabled");
        Arg<Mode> mode = Arguments.enumeration("mode", Mode.class);
        Arg<String> message = Arguments.greedyString("message");
        AtomicReference<String> captured = new AtomicReference<String>();
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.literal("set", set -> set.argument(count, one -> one.argument(price, two ->
                two.argument(enabled, three -> three.argument(mode, four ->
                        four.argument(message, five -> five.executes(context -> captured.set(
                                context.get(count) + ":" + context.get(price) + ":"
                                        + context.get(enabled) + ":" + context.get(mode) + ":"
                                        + context.get(message)))))))));
        CommandDispatcher dispatcher = new CommandDispatcher(spec);

        CommandResult result = dispatcher.execute(
                TestSenders.console().sender(),
                new String[]{"set", "3", "4.5", "yes", "safe", "hello", "world"});

        assertEquals(CommandResult.Status.SUCCESS, result.status());
        assertEquals("3:4.5:true:SAFE:hello world", captured.get());
    }

    @Test
    void resolvesPlayerAndProvidesBooleanEnumAndNearestSuggestions() {
        Player alex = (Player) TestSenders.player("Alex").sender();
        PlayerResolver resolver = new PlayerResolver() {
            @Override
            public Player findExact(String name) {
                return "Alex".equalsIgnoreCase(name) ? alex : null;
            }

            @Override
            public List<String> suggest(String prefix) {
                return prefix.toLowerCase().startsWith("a")
                        ? Collections.singletonList("Alex")
                        : Collections.<String>emptyList();
            }
        };
        Arg<Player> target = Arguments.player("target");
        Arg<Boolean> enabled = Arguments.bool("enabled");
        Arg<Mode> mode = Arguments.enumeration("mode", Mode.class);
        AtomicReference<Player> captured = new AtomicReference<Player>();
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.literal("target", node -> node.argument(target, value ->
                value.executes(context -> captured.set(context.get(target)))));
        spec.literal("toggle", node -> node.argument(enabled, value -> value.executes(context -> {
        })));
        spec.literal("mode", node -> node.argument(mode, value -> value.executes(context -> {
        })));
        spec.literal("reload", node -> node.executes(context -> {
        }));
        CommandDispatcher dispatcher = new CommandDispatcher(
                spec,
                resolver,
                PlainRichTextSink.INSTANCE);

        dispatcher.execute(TestSenders.console().sender(), new String[]{"target", "Alex"});

        assertSame(alex, captured.get());
        assertEquals(
                Arrays.asList("0", "1", "false", "no", "off", "on", "true", "yes"),
                dispatcher.complete(
                        TestSenders.console().sender(), new String[]{"toggle", ""}));
        assertEquals(Collections.singletonList("safe"), dispatcher.complete(
                TestSenders.console().sender(), new String[]{"mode", "s"}));
        CommandResult typo = dispatcher.execute(
                TestSenders.console().sender(), new String[]{"relod"});
        assertEquals(CommandResult.Status.UNKNOWN_ARGUMENT, typo.status());
        assertTrue(typo.message().plainText().contains("reload"));
        assertTrue(typo.message().plainText().contains("←"));
        assertTrue(typo.message().plainText().contains("relod"));
    }

    @Test
    void reportsIntegerErrorAtArgumentPosition() {
        Arg<Integer> count = Arguments.integer("count", 1, 5);
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.literal("set", node -> node.argument(count, value -> value.executes(context -> {
        })));
        CommandDispatcher dispatcher = new CommandDispatcher(spec);

        CommandResult result = dispatcher.execute(
                TestSenders.console().sender(),
                new String[]{"set", "many"});

        assertEquals(CommandResult.Status.INVALID_ARGUMENT, result.status());
        assertTrue(result.message().plainText().contains("需要整数"));
        assertTrue(result.message().plainText().contains("←"));
        assertTrue(result.message().plainText().contains("many"));
    }

    @Test
    void integerRangeMessageDegradesWhenBoundIsExtreme() {
        Arg<Integer> page = Arguments.integer("page", 1, Integer.MAX_VALUE);
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.literal("help", node -> node.argument(page, value -> value.executes(context -> {
        })));
        CommandDispatcher dispatcher = new CommandDispatcher(spec);

        CommandResult result = dispatcher.execute(
                TestSenders.console().sender(),
                new String[]{"help", "0"});

        assertEquals(CommandResult.Status.INVALID_ARGUMENT, result.status());
        String message = result.message().plainText();
        assertTrue(message.contains("≥ 1"));
        assertFalse(message.contains(String.valueOf(Integer.MAX_VALUE)));
    }

    @Test
    void ignoresEmptyTokensFromRepeatedSpaces() {
        Arg<String> message = Arguments.greedyString("message");
        AtomicReference<String> captured = new AtomicReference<String>();
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.literal("say", node -> node.argument(message, value ->
                value.executes(context -> captured.set(context.get(message)))));
        CommandDispatcher dispatcher = new CommandDispatcher(spec);

        CommandResult result = dispatcher.execute(
                TestSenders.console().sender(),
                new String[]{"", "say", "", "hello"});

        assertEquals(CommandResult.Status.SUCCESS, result.status());
        assertEquals("hello", captured.get());
    }

    @Test
    void customArgumentParsesAndSuggestsThroughExtensionPoint() {
        Arg<Integer> level = Arguments.custom(
                "level",
                input -> input.startsWith("lv") ? Integer.valueOf(input.substring(2)) : null,
                (sender, prefix) -> Arrays.asList("lv1", "lv2"));
        AtomicReference<Integer> captured = new AtomicReference<Integer>();
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.argument(level, node ->
                node.executes(context -> captured.set(context.get(level))));
        CommandDispatcher dispatcher = new CommandDispatcher(spec);

        assertEquals(
                CommandResult.Status.SUCCESS,
                dispatcher.execute(TestSenders.console().sender(), new String[]{"lv3"}).status());
        assertEquals(Integer.valueOf(3), captured.get());
        assertEquals(Arrays.asList("lv1", "lv2"), dispatcher.complete(
                TestSenders.console().sender(), new String[]{"lv"}));
        assertEquals(
                CommandResult.Status.INVALID_ARGUMENT,
                dispatcher.execute(TestSenders.console().sender(), new String[]{"bad"}).status());
    }

    @Test
    void rejectsDuplicateOrShadowedSiblingArguments() {
        CommandSpecImpl duplicate = CommandSpecImpl.command("demo");
        duplicate.argument(Arguments.integer("count"), node -> node.executes(context -> {
        }));
        assertThrows(
                IllegalArgumentException.class,
                () -> duplicate.argument(Arguments.string("count"), node ->
                        node.executes(context -> {
                        })));

        CommandSpecImpl shadowed = CommandSpecImpl.command("demo");
        shadowed.argument(Arguments.string("value"), node -> node.executes(context -> {
        }));
        assertThrows(
                IllegalStateException.class,
                () -> shadowed.argument(Arguments.integer("count"), node ->
                        node.executes(context -> {
                        })));
    }

    @Test
    void parsesStringAndUsesCustomSuggestions() {
        Arg<String> value = Arguments.string(
                "value",
                (sender, prefix) -> Arrays.asList("alpha", "beta"));
        AtomicReference<String> captured = new AtomicReference<String>();
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.literal("set", node -> node.argument(value, argument ->
                argument.executes(context -> captured.set(context.get(value)))));
        CommandDispatcher dispatcher = new CommandDispatcher(spec);

        dispatcher.execute(TestSenders.console().sender(), new String[]{"set", "literal"});

        assertEquals("literal", captured.get());
        assertEquals(Collections.singletonList("beta"), dispatcher.complete(
                TestSenders.console().sender(),
                new String[]{"set", "b"}));
    }

    @Test
    void choiceAndOptionalArgumentUseCanonicalAndDefaultValues() {
        Arg<String> choice = Arguments.choice("mode", "fast", "safe");
        Arg<Integer> optionalCount = Arguments.optional(Arguments.integer("count", 1, 10), 3);
        AtomicReference<String> captured = new AtomicReference<String>();
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.argument(choice, mode -> mode.argument(optionalCount, count ->
                count.executes(context -> captured.set(
                        context.get(choice) + ":" + context.get(optionalCount)))));
        CommandDispatcher dispatcher = new CommandDispatcher(spec);

        dispatcher.execute(TestSenders.console().sender(), new String[]{"FAST"});
        assertEquals("fast:3", captured.get());

        dispatcher.execute(TestSenders.console().sender(), new String[]{"safe", "7"});
        assertEquals("safe:7", captured.get());
        assertEquals(Collections.singletonList("safe"), dispatcher.complete(
                TestSenders.console().sender(),
                new String[]{"s"}));
    }
}
