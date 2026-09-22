package me.kzheart.klib.config;
import java.util.*;
import me.kzheart.klib.config.annotation.*;
import me.kzheart.klib.config.api.*;
import me.kzheart.klib.scope.ScopeImpl;
import me.kzheart.klib.scope.capability.ConfigCapability;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ConfigAnnotationsTest {
    @ConfigFile("settings.yml") public static class Settings {
        @Key("party-size") @Range(min = 1, max = 16) @Comment({"人数限制", "第二行"}) public int partySize = 4;
        public boolean enabled = true;
        public String message = "hello\nworld";
        public List<String> modes = Arrays.asList("easy", "hard");
        @Validate public void check() {
            if (enabled && message.isEmpty()) throw new ConfigValidationException("message required");
        }
    }
    @Test void annotationLoadAndFailedReloadKeepPreviousSnapshot() {
        ScopeImpl scope = new ScopeImpl("config");
        InMemoryConfigSource source = new InMemoryConfigSource("settings.yml", "party-size: 6\n");
        scope.registerCapability(ConfigCapability.class, new YamlConfigCapability((owner, path) -> {
            assertEquals("settings.yml", path); return source;
        }, new YamlConfigMapper()));
        ConfigDocument<Settings> doc = new Configs(scope).load(Settings.class);
        assertEquals(6, doc.value().partySize);
        Settings previous = doc.value();
        source.update("party-size: 30\n");
        ConfigException error = assertThrows(ConfigException.class, doc::reload);
        assertTrue(error.getMessage().contains("party-size")); assertSame(previous, doc.value());
        source.update("party-size: 8\nmessage: ''\n");
        assertThrows(ConfigException.class, doc::reload); assertSame(previous, doc.value());
        source.update("party-size: 8\n"); doc.reload(); assertEquals(8, doc.value().partySize);
        scope.close();
    }
    @Test void generatedDefaultsPreserveCommentsAliasesCollectionsAndMultilineStrings() {
        String yaml = ConfigDefaults.yaml(Settings.class);
        assertTrue(yaml.contains("# 人数限制")); assertTrue(yaml.contains("party-size"));
        Settings value = new YamlConfigMapper().read(YamlDocument.parse("settings.yml", yaml).root(), Settings.class);
        assertEquals(4, value.partySize); assertEquals("hello\nworld", value.message);
        assertEquals(Arrays.asList("easy", "hard"), value.modes);
    }
    public static class InvalidDefault { @Range(min=1, max=5) public int count = 0; }
    @Test void missingYamlFieldStillValidatesJavaDefault() {
        assertThrows(ConfigException.class, () -> new YamlConfigMapper().read(YamlDocument.parse("x.yml", "{}").root(), InvalidDefault.class));
    }
    public static class DuplicateKey { @Key("x") public int one; @Key("x") public int two; }
    @Test void duplicateAliasesAreRejected() {
        assertThrows(ConfigException.class, () -> new YamlConfigMapper().read(YamlDocument.parse("x.yml", "x: 1").root(), DuplicateKey.class));
    }
    public static class PrivateValidation { @Validate private void validate() { } }
    @Test void privateValidationIsNotSilentlyIgnored() {
        assertThrows(IllegalArgumentException.class, () -> new YamlConfigMapper().read(YamlDocument.parse("x.yml", "{}").root(), PrivateValidation.class));
    }
}
