package me.kzheart.klib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileConfigSourceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void extractsDefaultsMergesMissingValuesMigratesAndWritesVersion() throws Exception {
        Path file = temporaryDirectory.resolve("nested/config.yml");
        MigrationRunner migrations = new MigrationRunner()
                .add(1, Migrations.rename("old-name", "name"));
        FileConfigSource source = new FileConfigSource(
                file,
                "# shipped defaults\nold-name: klib\nport: 25565\n",
                0,
                migrations,
                false);

        YamlDocument first = source.load();

        assertTrue(Files.exists(file));
        assertEquals("klib", first.node("name").raw());
        assertEquals(Integer.valueOf(25565), first.node("port").raw());
        assertEquals(1, first.schemaVersion());
        String persisted = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(persisted.contains("# shipped defaults"));
        assertTrue(persisted.contains("_schema-version: 1"));
        assertFalse(persisted.contains("old-name:"));

        Files.write(
                file,
                ("# user comment\nname: custom\n_schema-version: 1\n")
                        .getBytes(StandardCharsets.UTF_8));
        YamlDocument merged = source.load();

        assertEquals("custom", merged.node("name").raw());
        assertEquals(Integer.valueOf(25565), merged.node("port").raw());
        assertFalse(merged.node("old-name").exists());
        assertTrue(source.load().toYaml().contains("# user comment"));
    }

    @Test
    void assumesShippedDefaultsUseLatestSchemaUnlessDeclaredOtherwise() {
        Path file = temporaryDirectory.resolve("current.yml");
        MigrationRunner migrations = new MigrationRunner()
                .add(1, Migrations.rename("name", "legacy-name"));
        FileConfigSource source = new FileConfigSource(
                file,
                "name: current\n",
                migrations,
                false);

        YamlDocument document = source.load();

        assertEquals("current", document.node("name").raw());
        assertFalse(document.node("legacy-name").exists());
        assertEquals(1, document.schemaVersion());
    }

    @Test
    void mapperFailureDoesNotPersistPreparedMigration() throws Exception {
        Path file = temporaryDirectory.resolve("invalid.yml");
        String original = "old-port: invalid\n";
        Files.write(file, original.getBytes(StandardCharsets.UTF_8));
        MigrationRunner migrations = new MigrationRunner()
                .add(1, Migrations.rename("old-port", "port"));
        FileConfigSource source = new FileConfigSource(file, "", migrations, false);
        ScopeImpl scope = new ScopeImpl("mapper-failure");

        assertThrows(
                ConfigMappingException.class,
                () -> YamlConfigDocument.open(
                        scope, source, new YamlConfigMapper(), NumericConfig.class));

        assertEquals(original, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        scope.close();
    }

    static final class NumericConfig {
        private int port;
    }
}
