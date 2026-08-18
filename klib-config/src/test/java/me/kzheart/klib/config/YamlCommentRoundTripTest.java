package me.kzheart.klib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class YamlCommentRoundTripTest {
    @Test
    void rejectsRecursiveAliasesBeforeMappingCanOverflowTheStack() {
        assertThrows(
                ConfigException.class,
                () -> YamlDocument.parse("recursive.yml", "a: &a\n  - *a\n"));
    }

    @Test
    void defaultMergeRetainsExistingCommentsAndValues() {
        YamlDocument document = YamlDocument.parse(
                "config.yml",
                "# server settings\n"
                        + "server:\n"
                        + "  # user-selected port\n"
                        + "  port: 25570\n"
                        + "custom: keep-me # untouched\n");
        YamlDocument defaults = YamlDocument.parse(
                "defaults.yml",
                "server:\n"
                        + "  port: 25565\n"
                        + "  # default bind host\n"
                        + "  host: localhost\n"
                        + "enabled: true\n");

        document.mergeDefaults(defaults);
        String rendered = document.toYaml();

        assertTrue(rendered.contains("# server settings"));
        assertTrue(rendered.contains("# user-selected port"));
        assertTrue(rendered.contains("# untouched"));
        assertTrue(rendered.contains("# default bind host"));
        assertEquals(Integer.valueOf(25570), document.node("server.port").raw());
        assertEquals("localhost", document.node("server.host").raw());
        assertEquals(Boolean.TRUE, document.node("enabled").raw());
        assertEquals("keep-me", document.node("custom").raw());
    }
}
