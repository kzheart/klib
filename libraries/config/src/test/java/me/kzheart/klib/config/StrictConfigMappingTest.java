package me.kzheart.klib.config;

import java.util.List;
import java.util.Map;
import me.kzheart.klib.config.annotation.Key;
import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StrictConfigMappingTest {
    private final YamlConfigMapper mapper = new YamlConfigMapper(UnknownKeyPolicy.REJECT);

    @Test
    void unknownNestedKeyHasExactKeyLocationOnEveryAttempt() {
        YamlDocument document = YamlDocument.parse("shop.yml", "database:\n  prot: 3306\n");
        for (int attempt = 0; attempt < 2; attempt++) {
            ConfigMappingException failure = assertThrows(ConfigMappingException.class,
                    () -> mapper.read(document.root(), Settings.class));
            assertEquals("shop.yml", failure.sourceName());
            assertEquals("database.prot", failure.path());
            assertEquals(2, failure.line());
            assertEquals(3, failure.column());
        }
    }

    @Test
    void aliasesAndFreeMapKeysAreAllowedButNestedPojoKeysAreChecked() {
        Settings settings = mapper.read(YamlDocument.parse("shop.yml",
                "display-name: Shop\nlabels: {anything: ok}\n_schema-version: 3\n").root(), Settings.class);
        assertEquals("Shop", settings.name);
        assertEquals("ok", settings.labels.get("anything"));
        assertThrows(ConfigMappingException.class, () -> mapper.read(
                YamlDocument.parse("shop.yml", "databases: [{prot: 2}]\n").root(), Settings.class));
        assertThrows(ConfigMappingException.class, () -> mapper.read(
                YamlDocument.parse("shop.yml", "servers: {lobby: {prot: 2}}\n").root(), Settings.class));
        assertThrows(ConfigMappingException.class, () -> mapper.read(
                YamlDocument.parse("shop.yml", "database: {_schema-version: 1}\n").root(), Settings.class));
    }

    @Test
    void strictReloadFailurePreservesPublishedIdentity() {
        ScopeImpl scope = new ScopeImpl("strict-config");
        InMemoryConfigSource source = new InMemoryConfigSource("shop.yml", "display-name: Old\n");
        YamlConfigDocument<Settings> document = YamlConfigDocument.open(scope, source, mapper, Settings.class);
        Settings previous = document.value();
        source.update("display-name: New\nmisspelled: true\n");
        assertThrows(ConfigMappingException.class, document::reload);
        assertSame(previous, document.value());
        source.update("display-name: New\n");
        document.reload();
        assertEquals("New", document.value().name);
        scope.close();
    }

    @Test
    void defaultMapperStillAcceptsUnknownKeysAndNullPolicyIsRejected() {
        assertEquals("Shop", new YamlConfigMapper().read(
                YamlDocument.parse("shop.yml", "display-name: Shop\nunused: true\n").root(), Settings.class).name);
        assertThrows(NullPointerException.class, () -> new YamlConfigMapper(null));
    }

    static final class Settings {
        @Key("display-name") public String name = "Default";
        public Database database = new Database();
        public Map<String, String> labels;
        public List<Database> databases;
        public Map<String, Database> servers;
    }

    static final class Database {
        public int port = 3306;
    }
}
