package me.kzheart.klib.command;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import me.kzheart.klib.command.api.CommandErrorHandler;
import me.kzheart.klib.lang.RichText;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommandErrorPolicyTest {
    @Test void businessChoosesItsOwnMessageAndReceivesParsedContext() {
        CommandSpecImpl spec = CommandSpecImpl.command("edit");
        Arg<Integer> count = Arguments.integer("count", 1, 9);
        AtomicReference<Throwable> observed = new AtomicReference<Throwable>();
        spec.errorHandler((call, failure) -> {
            assertEquals(3, call.get(count).intValue());
            observed.set(failure);
            call.sender().sendMessage("业务自己的提示");
        });
        IllegalStateException error = new IllegalStateException("database credential must not be disclosed automatically");
        spec.argument(count).executes(call -> { throw error; });
        TestSenders.SenderFixture sender = TestSenders.console();
        List<RichText> frameworkOutput = new ArrayList<RichText>();
        CommandDispatcher dispatcher = new CommandDispatcher(spec, BukkitPlayerResolver.INSTANCE, (who, text) -> frameworkOutput.add(text));
        assertEquals(CommandResult.Status.FAILED, dispatcher.execute(sender.sender(), new String[] {"3"}).status());
        assertSame(error, observed.get());
        assertTrue(frameworkOutput.isEmpty());
        assertEquals("业务自己的提示", sender.messages().get(0));
    }

    @Test void childPolicyOverridesRootAndRootOverridesModulePolicy() {
        AtomicInteger module = new AtomicInteger(), root = new AtomicInteger(), child = new AtomicInteger();
        CommandSpecImpl spec = CommandSpecImpl.command("test");
        spec.errorHandler((call, failure) -> root.incrementAndGet());
        spec.route("root").executes(call -> { throw new IllegalStateException(); });
        spec.route("child").errorHandler((call, failure) -> child.incrementAndGet()).executes(call -> { throw new IllegalStateException(); });
        CommandErrorHandler fallback = (call, failure) -> module.incrementAndGet();
        CommandDispatcher dispatcher = new CommandDispatcher(spec, BukkitPlayerResolver.INSTANCE, (sender, text) -> {}, DefaultCommandMessages.INSTANCE, null, fallback);
        dispatcher.execute(TestSenders.console().sender(), new String[] {"root"});
        dispatcher.execute(TestSenders.console().sender(), new String[] {"child"});
        assertEquals(0, module.get()); assertEquals(1, root.get()); assertEquals(1, child.get());
    }

    @Test void failingBusinessErrorHandlerDoesNotReplaceOriginalFailureOrEmitGenericMessage() {
        CommandSpecImpl spec = CommandSpecImpl.command("test");
        spec.errorHandler((call, failure) -> { throw new IllegalStateException("handler failed"); });
        spec.executes(call -> { throw new IllegalArgumentException("original failure"); });
        List<RichText> sent = new ArrayList<RichText>();
        CommandDispatcher dispatcher = new CommandDispatcher(spec, BukkitPlayerResolver.INSTANCE, (sender, text) -> sent.add(text));
        assertEquals(CommandResult.Status.FAILED, dispatcher.execute(TestSenders.console().sender(), new String[0]).status());
        assertTrue(sent.isEmpty());
    }

    @Test void fatalErrorsRemainFatalAfterBusinessFeedback() {
        CommandSpecImpl spec = CommandSpecImpl.command("test");
        AtomicInteger feedback = new AtomicInteger();
        AssertionError fatal = new AssertionError("fatal");
        spec.errorHandler((call, failure) -> feedback.incrementAndGet());
        spec.executes(call -> { throw fatal; });
        CommandDispatcher dispatcher = new CommandDispatcher(spec);
        assertSame(fatal, assertThrows(AssertionError.class, () -> dispatcher.execute(TestSenders.console().sender(), new String[0])));
        assertEquals(1, feedback.get());
    }
}
