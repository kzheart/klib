package me.kzheart.klib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicInteger;
import me.kzheart.klib.scope.Disposable;
import org.junit.jupiter.api.Test;

class KLoggerTest {
    @Test
    void boundsRecentLinesAndSkipsDisabledDebug() {
        KLogger logger = new KLogger(Logger.getLogger("KLoggerTest"), 2);
        logger.setDebug("storage", true);

        logger.info("one");
        logger.debug("storage", "two");
        logger.debug("command", "hidden");
        logger.warn("three");

        assertEquals(2, logger.recentLines().size());
        assertTrue(logger.recentLines().get(0).contains("two"));
        assertTrue(logger.recentLines().get(1).contains("three"));
        assertFalse(logger.isDebugEnabled("command"));
    }

    @Test
    void supportsGlobalAndModuleDebugSwitches() {
        KLogger logger = new KLogger(Logger.getLogger("KLoggerTest"));

        logger.setDebug("storage", true);
        assertTrue(logger.isDebugEnabled("storage"));
        assertFalse(logger.isDebugEnabled("command"));

        logger.setDebug("*", true);
        assertTrue(logger.isDebugEnabled("command"));
    }

    @Test
    void errorObserverStopsReceivingAfterDisposal() {
        AtomicInteger observed = new AtomicInteger();
        KLogger logger = new KLogger(Logger.getLogger("KLoggerObserverTest"));
        Disposable registration = logger.onError(
                (message, failure) -> observed.incrementAndGet());

        logger.error("first", new IllegalStateException("boom"));
        registration.dispose();
        logger.error("second", new IllegalStateException("boom"));

        assertEquals(1, observed.get());
    }

    @Test
    void errorObserverCannotReenterObserversOnTheSameThread() throws Exception {
        AtomicInteger observed = new AtomicInteger();
        KLogger logger = new KLogger(Logger.getLogger("KLoggerReentryTest"));
        logger.onError((message, failure) -> {
            observed.incrementAndGet();
            logger.error("nested", new IllegalStateException("nested"));
        });

        logger.error("outer", new IllegalStateException("outer"));
        assertEquals(1, observed.get());

        Thread otherThread = new Thread(() ->
                logger.error("other", new IllegalStateException("other")));
        otherThread.start();
        otherThread.join();
        assertEquals(2, observed.get());
    }
}
