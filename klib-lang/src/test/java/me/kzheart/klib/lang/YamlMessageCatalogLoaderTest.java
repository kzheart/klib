package me.kzheart.klib.lang;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlMessageCatalogLoaderTest {
    @Test
    void decodesRichYamlEntryWithStyleAndActions() {
        Map<String, Object> click = new LinkedHashMap<String, Object>();
        click.put("type", "run_command");
        click.put("value", "/inspect");
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("text", "inspect");
        entry.put("color", "red");
        entry.put("bold", Boolean.TRUE);
        entry.put("hover", "details");
        entry.put("click", click);
        Map<String, Object> section = new LinkedHashMap<String, Object>();
        section.put("entry", entry);
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("admin", section);

        RichText rich = YamlMessageCatalogLoader.fromDecodedMap(root)
                .findRich("admin.entry")
                .get();
        RichTextSegment segment = rich.segments().get(0);

        assertEquals("inspect", segment.text());
        assertEquals(MessageColor.RED, segment.color());
        assertTrue(segment.bold());
        assertEquals(TextAction.Type.HOVER_TEXT, segment.hover().type());
        assertEquals("details", segment.hover().value());
        assertEquals(TextAction.Type.RUN_COMMAND, segment.click().type());
        assertEquals("/inspect", segment.click().value());
    }
}
