package me.kzheart.klib.config;

import java.io.Serializable;

import me.kzheart.klib.config.annotation.Comment;
import me.kzheart.klib.config.annotation.Key;
import me.kzheart.klib.config.annotation.Range;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTypePlanRegressionTest {
    static class Base {
        @Key("base-count") @Comment("Inherited default") @Range(min = 1, max = 8)
        private int count = 4;
    }

    static final class Settings extends Base {
        private String name = "default";
        private transient String ignored = "unchanged";
        private static String shared = "static";
    }

    @Test
    void sharedPlanKeepsJavaNoArgDefaultsInheritedAliasesAndIgnoredFields() {
        YamlConfigMapper mapper = new YamlConfigMapper();
        Settings value = mapper.read(YamlDocument.parse("java.yml",
                "base-count: 7\nname: changed\nignored: discarded\nshared: discarded\n").root(), Settings.class);
        assertEquals(7, ((Base) value).count);
        assertEquals("changed", value.name);
        assertEquals("unchanged", value.ignored);
        assertEquals("static", Settings.shared);
        Settings defaults = mapper.read(YamlDocument.parse("java.yml", "{}").root(), Settings.class);
        assertEquals(4, ((Base) defaults).count);
        assertEquals("default", defaults.name);
    }

    @Test
    void sharedPlanDefaultsHaveSameInheritedNamesAndCommentsAsMapping() {
        String yaml = ConfigDefaults.yaml(Settings.class);
        assertTrue(yaml.contains("# Inherited default"));
        assertTrue(yaml.contains("base-count"));
        Settings value = new YamlConfigMapper().read(YamlDocument.parse("java.yml", yaml).root(), Settings.class);
        assertEquals(4, ((Base) value).count);
        assertEquals("default", value.name);
    }

    static final class FinalField {
        private final String name = "fixed";
    }

    @Test
    void javaFinalFieldsStillFailRatherThanBeingTreatedAsKotlinConstructorParameters() {
        assertThrows(ConfigMappingException.class, () -> new YamlConfigMapper().read(
                YamlDocument.parse("java.yml", "name: changed\n").root(), FinalField.class));
    }

    static final class Empty { }

    @Test
    void emptyDefaultObjectProducesValidMapping() {
        assertEquals("{}\n", ConfigDefaults.yaml(Empty.class));
    }

    static class IgnoredParent implements Serializable {
        private static final long serialVersionUID = 1L;
        private transient String cache = "parent";
    }

    static final class IgnoredChild extends IgnoredParent {
        private static final long serialVersionUID = 2L;
        private transient String cache = "child";
        private String name = "default";
    }

    @Test
    void inheritedIgnoredNamesDoNotBecomeDuplicateMappedKeys() {
        IgnoredChild value = new YamlConfigMapper().read(YamlDocument.parse("java.yml",
                "name: mapped\nserialVersionUID: 3\ncache: external\n").root(), IgnoredChild.class);
        assertEquals("mapped", value.name);
        assertEquals("parent", ((IgnoredParent) value).cache);
        assertEquals("child", value.cache);
        String defaults = ConfigDefaults.yaml(IgnoredChild.class);
        assertFalse(defaults.contains("serialVersionUID"));
        assertFalse(defaults.contains("cache"));
    }

    static final class IgnoredAlias {
        @Key("name") private transient String cache = "local";
        private String name = "default";
    }

    @Test
    void ignoredAliasCannotHideAnActualMappedField() {
        IgnoredAlias value = new YamlConfigMapper().read(YamlDocument.parse("java.yml",
                "name: mapped\n").root(), IgnoredAlias.class);
        assertEquals("mapped", value.name);
        assertEquals("local", value.cache);
        ConfigDefaults.yaml(IgnoredAlias.class);
    }

    static class DuplicateParent {
        private String name = "parent";
    }

    static final class DuplicateChild extends DuplicateParent {
        private String name = "child";
    }

    @Test
    void actualMappedDuplicatesAcrossInheritanceStillFail() {
        assertThrows(ConfigMappingException.class, () -> new YamlConfigMapper().read(
                YamlDocument.parse("java.yml", "{}").root(), DuplicateChild.class));
        assertThrows(ConfigException.class, () -> ConfigDefaults.yaml(DuplicateChild.class));
    }
}
