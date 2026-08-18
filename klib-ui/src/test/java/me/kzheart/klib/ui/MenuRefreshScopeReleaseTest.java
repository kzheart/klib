package me.kzheart.klib.ui;

import me.kzheart.klib.scheduler.SchedulerFactory;
import me.kzheart.klib.scheduler.Ticks;
import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuRefreshScopeReleaseTest {
    @Test
    void menuCloseCancelsRefreshTask() {
        ManualSchedulerFactory scheduler = new ManualSchedulerFactory();
        ScopeImpl parent = new ScopeImpl("root");
        parent.registerCapability(SchedulerFactory.class, scheduler);
        MenuSession menu = MenuSession.open(parent, "paged",
                MenuCompiler.compile(MenuTemplate.builder("Paged", 1).build()),
                items -> Collections.emptyList());
        AtomicInteger refreshes = new AtomicInteger();
        menu.refreshEvery(Ticks.of(1), refreshes::incrementAndGet);
        ManualSchedulerFactory.ManualTask refresh = scheduler.latest();

        refresh.run();
        menu.close(CloseReason.PLAYER);
        refresh.run();

        assertEquals(1, refreshes.get());
        assertTrue(refresh.isCancelled());
    }
}
