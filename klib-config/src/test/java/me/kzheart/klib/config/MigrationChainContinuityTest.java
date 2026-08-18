package me.kzheart.klib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MigrationChainContinuityTest {
    @Test
    void rejectsGapBeforeMutatingDocument() {
        YamlDocument document = YamlDocument.parse("config.yml", "old: value\n");
        MigrationRunner runner = new MigrationRunner()
                .add(1, Migrations.rename("old", "middle"))
                .add(3, Migrations.rename("middle", "current"));

        assertThrows(ConfigException.class, () -> runner.migrate(document));

        assertEquals("value", document.node("old").raw());
        assertFalse(document.node("_schema-version").exists());
    }

    @Test
    void persistsSchemaVersionAfterContinuousChain() {
        YamlDocument document = YamlDocument.parse("config.yml", "old: value\n");
        MigrationRunner runner = new MigrationRunner()
                .add(1, Migrations.rename("old", "middle"))
                .add(2, Migrations.rename("middle", "current"));

        assertEquals(2, runner.migrate(document));

        assertEquals(2, document.schemaVersion());
        assertEquals(Integer.valueOf(2), document.node("_schema-version").raw());
        assertEquals("value", document.node("current").raw());
    }
}
