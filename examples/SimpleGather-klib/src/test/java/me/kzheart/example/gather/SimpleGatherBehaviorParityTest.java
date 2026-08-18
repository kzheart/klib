package me.kzheart.example.gather;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SimpleGatherBehaviorParityTest {
    @Test
    void locksOriginalCommandAndTagSurface() {
        assertEquals("simplegather", GatherContract.ROOT_COMMAND);
        assertEquals("sg", GatherContract.COMMAND_ALIAS);
        assertEquals("simplegather:type", GatherContract.TOOL_TYPE_TAG);
        assertEquals("simplegather:durability", GatherContract.TOOL_DURABILITY_TAG);
        assertEquals(Arrays.asList(
                "help", "reload", "list", "info", "stats", "generate", "spawns", "give", "dump"),
                GatherContract.COMMANDS);
    }

    @Test
    void preservesWrongToolDurabilityProgressAndCompletionRules() {
        GatherSession session = new GatherSession("mining", 3);

        assertEquals(GatherSession.Result.WRONG_TOOL, session.hit("garden", 10, 1));
        assertEquals(GatherSession.Result.TOOL_BROKEN, session.hit("mining", 0, 1));
        assertEquals(GatherSession.Result.STARTED, session.hit("mining", 10, 1));
        assertEquals(GatherSession.Result.PROGRESSED, session.hit("mining", 9, 1));
        assertEquals(GatherSession.Result.COMPLETED, session.hit("mining", 8, 1));
        assertEquals(0, session.health());

        session.reset();
        assertEquals(3, session.health());
    }

    @Test
    void runtimeOwnsOneSessionPerBlockAndRemovesItAfterCompletion() {
        GatherRuntime runtime = new GatherRuntime(3);

        assertEquals(GatherSession.Result.WRONG_TOOL, runtime.hit("world:1:2:3", "garden", 10));
        assertEquals(0, runtime.activeSessions());
        assertEquals(GatherSession.Result.STARTED, runtime.hit("world:1:2:3", "mining", 10));
        assertEquals(GatherSession.Result.PROGRESSED, runtime.hit("world:1:2:3", "mining", 9));
        assertEquals(1, runtime.activeSessions());
        assertEquals(GatherSession.Result.COMPLETED, runtime.hit("world:1:2:3", "mining", 8));
        assertEquals(0, runtime.activeSessions());
    }
}
