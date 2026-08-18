package me.kzheart.klib.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerVersionTest {
    @Test
    void parsesPlainAndDecoratedVersions() {
        assertEquals(ServerVersion.of(1, 12, 2), ServerVersion.parse("1.12.2"));
        assertEquals(ServerVersion.of(1, 20, 4), ServerVersion.parse("git-Paper-496 (MC: 1.20.4)"));
        assertEquals(ServerVersion.of(1, 20, 0), ServerVersion.parse("1.20-R0.1-SNAPSHOT"));
    }

    @Test
    void rejectsUnknownFormat() {
        assertThrows(IllegalArgumentException.class, () -> ServerVersion.parse("unknown"));
    }
}
