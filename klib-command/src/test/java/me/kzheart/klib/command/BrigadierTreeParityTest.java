package me.kzheart.klib.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrigadierTreeParityTest {
    @Test
    void projectionKeepsTypedTreeShapeAndRestrictions() {
        Arg<Integer> count = Arguments.optional(Arguments.integer("count", 1, 10), 3);
        Arg<String> message = Arguments.greedyString("message");
        CommandSpecImpl spec = CommandSpecImpl.command("demo");
        spec.permission("demo.use");
        spec.literal("send", send -> send.playerOnly().argument(count, amount ->
                amount.argument(message, text -> text.executes(context -> {
                }))));

        BrigadierTree tree = BrigadierTree.from(spec);

        assertEquals(BrigadierTree.Kind.ROOT, tree.kind());
        assertEquals("demo", tree.token());
        assertEquals("demo.use", tree.permission());
        BrigadierTree send = tree.children().get(0);
        assertEquals(BrigadierTree.Kind.LITERAL, send.kind());
        assertEquals("send", send.token());
        assertTrue(send.playerOnly());
        BrigadierTree amount = send.children().get(0);
        assertEquals(BrigadierTree.Kind.ARGUMENT, amount.kind());
        assertEquals("count", amount.token());
        assertFalse(amount.greedy());
        BrigadierTree text = amount.children().get(0);
        assertEquals("message", text.token());
        assertTrue(text.greedy());
        assertTrue(text.handler());
    }
}
