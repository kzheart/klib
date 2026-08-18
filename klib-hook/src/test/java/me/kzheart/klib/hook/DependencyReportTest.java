package me.kzheart.klib.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DependencyReportTest {

    @Test
    void reportsAvailableAndNoopDependenciesWithoutThrowingForMissingPlugins() {
        Hook<String> available = Hooks.available("Vault", "service");
        Hook<String> missing = Hooks.orNoop("PlayerPoints", () -> null, "noop");
        DependencyReport report = DependencyReport.builder().add(available).add(missing).build();

        assertTrue(available.available());
        assertFalse(missing.available());
        assertEquals(DependencyStatus.NOOP, missing.status());
        assertEquals(2, report.lines().size());
    }
}
