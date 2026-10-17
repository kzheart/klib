package me.kzheart.klib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MigrationIdempotencyTest {
    @Test
    void renameCanRunRepeatedlyWithoutChangingTheSecondResult() {
        YamlDocument document = YamlDocument.parse(
                "config.yml",
                "# identity\nold-name: klib # preserve this\nother: value\n");
        MigrationRunner runner = new MigrationRunner()
                .add(2, Migrations.rename("old-name", "name"));

        assertEquals(2, runner.migrate(document, 1));
        String first = document.toYaml();
        assertEquals(2, runner.migrate(document, 1));
        String second = document.toYaml();

        assertEquals(first, second);
        assertFalse(document.node("old-name").exists());
        assertEquals("klib", document.node("name").raw());
        assertTrue(second.contains("# identity"));
        assertTrue(second.contains("# preserve this"));
    }

    @Test
    void existingDestinationWinsWithoutLeavingTheLegacyKey() {
        YamlDocument document = YamlDocument.parse(
                "config.yml",
                "old: stale\ncurrent: chosen\n");

        Migrations.rename("old", "current").apply(document);

        assertFalse(document.node("old").exists());
        assertEquals("chosen", document.node("current").raw());
    }
}
