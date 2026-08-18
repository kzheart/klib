package me.kzheart.klib.command;

import me.kzheart.klib.command.api.CommandContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandContextByNameTest {
    @Test
    void argumentsCanBeReadByName() {
        Arg<Integer> amount = Arguments.integer("amount");
        Arg<String> type = Arguments.choice("type", "mining", "garden");
        AtomicReference<CommandContext> captured = new AtomicReference<CommandContext>();
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.argument(type, typeNode -> typeNode.argument(amount, amountNode ->
                amountNode.executes(captured::set)));
        CommandDispatcher dispatcher = dispatcher(spec);

        dispatcher.execute(TestSenders.console().sender(), new String[]{"mining", "7"});

        CommandContext context = captured.get();
        assertNotNull(context);
        assertEquals("mining", context.get("type", String.class));
        assertEquals(Integer.valueOf(7), context.get("amount", Integer.class));
        assertEquals("mining", context.find("type").orElse(null));
        assertFalse(context.find("missing").isPresent());
    }

    @Test
    void optionalWrapperValuesAreReachableByName() {
        Arg<Integer> base = Arguments.integer("amount", 1, 64);
        Arg<Integer> amount = Arguments.optional(base, Integer.valueOf(1));
        AtomicReference<CommandContext> captured = new AtomicReference<CommandContext>();
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.argument(amount, node -> node.executes(captured::set));
        CommandDispatcher dispatcher = dispatcher(spec);

        dispatcher.execute(TestSenders.console().sender(), new String[0]);
        assertEquals(Integer.valueOf(1), captured.get().get("amount", Integer.class));

        dispatcher.execute(TestSenders.console().sender(), new String[]{"9"});
        CommandContext context = captured.get();
        assertEquals(Integer.valueOf(9), context.get("amount", Integer.class));
        // 包装实例可用，原始实例不在解析结果里——这正是按名读取要绕开的陷阱。
        assertEquals(Integer.valueOf(9), context.get(amount));
        assertThrows(IllegalArgumentException.class, () -> context.get(base));
    }

    @Test
    void unparsedArgumentErrorListsAvailableNamesAndHint() {
        Arg<String> type = Arguments.choice("type", "mining");
        Arg<String> other = Arguments.string("other");
        AtomicReference<CommandContext> captured = new AtomicReference<CommandContext>();
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.argument(type, node -> node.executes(captured::set));
        CommandDispatcher dispatcher = dispatcher(spec);

        dispatcher.execute(TestSenders.console().sender(), new String[]{"mining"});
        CommandContext context = captured.get();

        IllegalArgumentException byRef = assertThrows(
                IllegalArgumentException.class, () -> context.get(other));
        assertTrue(byRef.getMessage().contains("other"));
        assertTrue(byRef.getMessage().contains("[type]"));
        assertTrue(byRef.getMessage().contains("Arguments.optional"));

        IllegalArgumentException byName = assertThrows(
                IllegalArgumentException.class, () -> context.get("other", String.class));
        assertTrue(byName.getMessage().contains("[type]"));
    }

    private static CommandDispatcher dispatcher(CommandSpecImpl spec) {
        return new CommandDispatcher(
                spec,
                BukkitPlayerResolver.INSTANCE,
                (sender, text) -> {
                },
                DefaultCommandMessages.INSTANCE);
    }
}
