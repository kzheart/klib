package me.kzheart.klib.config;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigSectionCreationTest {
    @Test void childCreationIsVisibleInRootAndSerializedDocument() {
        YamlDocument document = YamlDocument.parse("conversation.yml", "talk:\n  text: hello\n");
        ConfigNode talk = document.root().child("talk");
        ConfigNode transfer = talk.createSection("transfer");
        assertEquals("talk.transfer", transfer.path());
        transfer.createSection("nested");
        assertTrue(document.node("talk.transfer.nested").raw() instanceof Map);
        assertTrue(YamlDocument.parse("roundtrip.yml", document.toYaml()).node("talk.transfer.nested").exists());
    }
    @Test void replacingSectionDropsContentsButKeepsKeyCommentAndLiteralDots() {
        YamlDocument document = YamlDocument.parse("test.yml", "# retain comment\nold:\n  value: 1\n");
        ConfigNode old = document.root().child("old");
        document.root().createSection("old");
        assertTrue(document.node("old").keys().isEmpty());
        assertTrue(old.child("value").exists());
        assertTrue(document.toYaml().contains("# retain comment"));
        document.root().createSection("literal.dot");
        assertTrue(document.root().child("literal.dot").exists());
        assertFalse(document.root().child("literal").exists());
    }
    @Test void invalidParentDoesNotCreateUnattachedSections() {
        YamlDocument document = YamlDocument.parse("test.yml", "value: scalar\n");
        assertThrows(ConfigMappingException.class, () -> document.node("missing").createSection("child"));
        assertThrows(ConfigMappingException.class, () -> document.node("value").createSection("child"));
        assertFalse(document.node("missing").exists());
        assertEquals("scalar", document.node("value").raw());
    }
}
