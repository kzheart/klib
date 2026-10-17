package me.kzheart.klib.ui;

import me.kzheart.klib.scheduler.SchedulerFactory;
import me.kzheart.klib.scheduler.Ticks;
import me.kzheart.klib.scope.ScopeImpl;
import me.kzheart.klib.ui.prompt.ChatPrompt;
import me.kzheart.klib.ui.prompt.PromptOutcome;
import me.kzheart.klib.ui.prompt.PromptSession;
import me.kzheart.klib.ui.prompt.PromptStatus;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptTimeoutTest {
    @Test
    void promptTimesOutAndRejectsLateInput() {
        ManualSchedulerFactory scheduler = new ManualSchedulerFactory();
        ScopeImpl scope = new ScopeImpl("prompt");
        scope.registerCapability(SchedulerFactory.class, scheduler);
        PromptSession<Integer> session = ChatPrompt.start(scope, Ticks.of(20), message -> {
            try {
                return Optional.of(Integer.valueOf(message));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        });

        assertFalse(session.submit("nope"));
        scheduler.latest().run();
        PromptOutcome<Integer> outcome = session.completion().toCompletableFuture().join();

        assertEquals(PromptStatus.TIMED_OUT, outcome.status());
        assertFalse(outcome.value().isPresent());
        assertFalse(session.submit("42"));
    }

    @Test
    void answerAndScopeCloseCancelTimeout() {
        ManualSchedulerFactory scheduler = new ManualSchedulerFactory();
        ScopeImpl scope = new ScopeImpl("prompt");
        scope.registerCapability(SchedulerFactory.class, scheduler);
        PromptSession<String> answered = ChatPrompt.start(scope, Ticks.of(20), Optional::of);
        ManualSchedulerFactory.ManualTask answerTimeout = scheduler.latest();

        assertTrue(answered.submit("ok"));
        assertEquals(PromptStatus.ANSWERED, answered.status());
        assertTrue(answerTimeout.isCancelled());

        PromptSession<String> cancelled = ChatPrompt.start(scope, Ticks.of(20), Optional::of);
        scope.close();
        assertEquals(PromptStatus.CANCELLED, cancelled.status());
    }

    @Test
    void everyTerminalStatusDetachesPromptFromOwnerScope() throws Exception {
        assertTerminalPromptDetaches(PromptStatus.ANSWERED);
        assertTerminalPromptDetaches(PromptStatus.CANCELLED);
        assertTerminalPromptDetaches(PromptStatus.TIMED_OUT);
    }

    private static void assertTerminalPromptDetaches(PromptStatus status) throws Exception {
        ManualSchedulerFactory scheduler = new ManualSchedulerFactory();
        ScopeImpl scope = new ScopeImpl("prompt-" + status.name().toLowerCase());
        scope.registerCapability(SchedulerFactory.class, scheduler);
        PromptSession<String> session = ChatPrompt.start(scope, Ticks.of(20), Optional::of);
        int installed = resourceCount(scope);

        if (status == PromptStatus.ANSWERED) {
            session.submit("ok");
        } else if (status == PromptStatus.CANCELLED) {
            session.cancel();
        } else {
            scheduler.latest().run();
        }

        assertEquals(status, session.status());
        assertEquals(installed - 1, resourceCount(scope),
                "terminal prompt must remove its own scope registration");
        scope.close();
    }

    private static int resourceCount(ScopeImpl scope) throws Exception {
        Method method = ScopeImpl.class.getDeclaredMethod("resourceCount");
        method.setAccessible(true);
        return ((Integer) method.invoke(scope)).intValue();
    }
}
