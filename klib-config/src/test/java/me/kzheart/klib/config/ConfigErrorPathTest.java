package me.kzheart.klib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConfigErrorPathTest {
    @Test
    void typeFailureContainsFileAndCompleteFieldPath() {
        YamlDocument document = YamlDocument.parse(
                "plugins/gather.yml",
                "database:\n  pool:\n    size: many\n");

        ConfigMappingException failure = assertThrows(
                ConfigMappingException.class,
                () -> new YamlConfigMapper().read(document.root(), RootConfig.class));

        assertEquals("plugins/gather.yml", failure.sourceName());
        assertEquals("database.pool.size", failure.path());
        assertTrue(failure.hasLocation());
        assertEquals(3, failure.line());
        assertEquals(11, failure.column());
        assertTrue(failure.getMessage()
                .contains("plugins/gather.yml:3:11 (database.pool.size)"));
        assertTrue(failure.getMessage().contains("expected a number"));
    }

    @Test
    void invalidYamlReportsProblemLine() {
        ConfigException failure = assertThrows(
                ConfigException.class,
                () -> YamlDocument.parse("plugins/gather.yml", "database:\n  host: [unclosed\n"));

        assertTrue(failure.getMessage().contains("plugins/gather.yml:"));
        assertTrue(failure.getMessage().contains("(<root>): invalid YAML"),
                failure.getMessage());
    }

    static final class RootConfig {
        private Database database = new Database();
    }

    static final class Database {
        private Pool pool = new Pool();
    }

    static final class Pool {
        private int size = 8;
    }
}
