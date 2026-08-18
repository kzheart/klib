package me.kzheart.klib.command;

import me.kzheart.klib.lang.RichText;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandUsageFeedbackTest {
    @Test
    void missingRequiredArgumentShowsUsageOfTheCurrentNode() {
        List<RichText> sent = new ArrayList<RichText>();
        Arg<String> player = Arguments.string("player");
        Arg<Integer> amount = Arguments.optional(
                Arguments.integer("amount", 1, 64), Integer.valueOf(1));
        CommandSpecImpl spec = CommandSpecImpl.command("gather");
        spec.literal("give", give -> give.argument(player, playerNode ->
                playerNode.argument(amount, amountNode -> amountNode.executes(context -> {
                }))));
        spec.literal("list", list -> list.executes(context -> {
        }));
        CommandDispatcher dispatcher = dispatcher(spec, sent);

        CommandResult result = dispatcher.execute(
                TestSenders.console().sender(), new String[]{"give"});

        assertEquals(CommandResult.Status.INCOMPLETE, result.status());
        assertEquals("用法: /gather give <player> [amount]", result.message().plainText());
        assertEquals(1, sent.size());
    }

    @Test
    void multipleBranchesAreListedOnSeparateLines() {
        List<RichText> sent = new ArrayList<RichText>();
        Arg<String> player = Arguments.choice("player", "alex", "steve");
        CommandSpecImpl spec = CommandSpecImpl.command("gather");
        spec.literal("give", give -> {
            give.literal("all", all -> all.executes(context -> {
            }));
            give.argument(player, playerNode -> playerNode.executes(context -> {
            }));
        });
        CommandDispatcher dispatcher = dispatcher(spec, sent);

        CommandResult result = dispatcher.execute(
                TestSenders.console().sender(), new String[]{"give"});

        assertEquals(CommandResult.Status.INCOMPLETE, result.status());
        assertEquals(
                "用法: /gather give all\n用法: /gather give <player>",
                result.message().plainText());
    }

    @Test
    void bareRootCommandStillShowsTheHelpPage() {
        List<RichText> sent = new ArrayList<RichText>();
        CommandSpecImpl spec = CommandSpecImpl.command("gather");
        spec.literal("list", list -> list.executes(context -> {
        }));
        CommandDispatcher dispatcher = dispatcher(spec, sent);

        CommandResult result = dispatcher.execute(TestSenders.console().sender(), new String[0]);

        assertEquals(CommandResult.Status.HELP, result.status());
        assertTrue(result.message().plainText().contains("/gather 帮助"));
    }

    @Test
    void inaccessibleBranchesFallBackToTheHelpPage() {
        List<RichText> sent = new ArrayList<RichText>();
        Arg<String> player = Arguments.string("player");
        CommandSpecImpl spec = CommandSpecImpl.command("gather");
        spec.literal("give", give -> give.argument(player, playerNode -> playerNode
                .permission("gather.give.other")
                .executes(context -> {
                })));
        CommandDispatcher dispatcher = dispatcher(spec, sent);

        CommandResult result = dispatcher.execute(
                TestSenders.console().sender(), new String[]{"give"});

        assertEquals(CommandResult.Status.HELP, result.status());
    }

    private static CommandDispatcher dispatcher(CommandSpecImpl spec, List<RichText> sent) {
        return new CommandDispatcher(
                spec,
                BukkitPlayerResolver.INSTANCE,
                (sender, text) -> sent.add(text),
                DefaultCommandMessages.INSTANCE);
    }
}
