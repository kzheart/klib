package me.kzheart.klib.config;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import me.kzheart.klib.config.annotation.Key;
import org.junit.jupiter.api.Test;

class ValueDocumentTest {
    @Test void scalarTypesArePreservedWithoutYamlConversionOrStringUnwrap() {
        LocalDate date = LocalDate.of(2026, 10, 2);
        BigInteger big = new BigInteger("9223372036854775808");
        ValueDocument doc = document(map("date", date, "big", big, "bool", true, "quoted", "null", "unicode", "\\u0041"));
        assertSame(date, doc.node("date").raw()); assertSame(big, doc.node("big").raw());
        assertEquals(Boolean.TRUE, doc.node("bool").raw());
        assertEquals("null", doc.node("quoted").raw()); assertEquals("\\u0041", doc.node("unicode").raw());
    }

    @Test void snapshotsAndOwnedContainersDoNotShareMutableMapsOrLists() {
        Map<String, Object> child = map("a", 1);
        List<Object> list = new ArrayList<Object>(); list.add(child);
        Map<String, Object> source = map("list", list);
        ValueDocument doc = document(source);
        source.clear(); child.put("a", 2); list.clear();
        assertEquals(1, doc.node("list").index(0).child("a").raw());
        Map<?, ?> snapshot = (Map<?, ?>) doc.root().raw();
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        assertThrows(UnsupportedOperationException.class, ((List<?>) snapshot.get("list"))::clear);
        Map<?, ?> element = (Map<?, ?>) ((List<?>) snapshot.get("list")).get(0);
        assertThrows(UnsupportedOperationException.class, element::clear);
        doc.node("list").index(0).createSection("added");
        assertFalse(element.containsKey("added"));
        assertTrue(((Map<?, ?>) doc.node("list").index(0).raw()).containsKey("added"));
    }

    @Test void nullAndMissingAreDifferentAndScalarTraversalNeverCreatesParents() {
        ValueDocument doc = document(map("nil", null, "scalar", "text"));
        assertTrue(doc.node("nil").exists()); assertNull(doc.node("nil").raw());
        assertFalse(doc.node("missing").exists()); assertNull(doc.node("missing").raw());
        assertFalse(doc.node("missing.child").exists());
        assertThrows(ConfigMappingException.class, () -> doc.node("nil.child"));
        assertThrows(ConfigMappingException.class, () -> doc.node("scalar.child"));
        assertThrows(ConfigMappingException.class, () -> doc.node("missing").createSection("child"));
        assertFalse(doc.node("missing").exists());
        assertThrows(ConfigMappingException.class, () -> doc.node("nil").sectionText());
    }

    @Test void literalNamePathAndIndexViewsWorkForBothBackends() {
        ValueDocument doc = document(map("literal.dot", map("a", 1), "literal", map("dot", 2), "list", Arrays.asList(map("k.dot", 3), null)));
        assertEquals("", doc.root().name()); assertEquals("literal.dot", doc.root().child("literal.dot").name());
        assertEquals(1, doc.root().child("literal.dot").child("a").raw()); assertEquals(2, doc.node("literal.dot").raw());
        assertEquals("[0]", doc.node("list").index(0).name());
        assertEquals("list[0].k.dot", doc.node("list").index(0).child("k.dot").path());
        assertEquals("k.dot", doc.node("list").index(0).child("k.dot").name());
        assertTrue(doc.node("list").index(1).exists()); assertNull(doc.node("list").index(1).raw());
        assertThrows(IndexOutOfBoundsException.class, () -> doc.node("list").index(2));
        assertThrows(IndexOutOfBoundsException.class, () -> doc.node("list").index(-1));
        assertThrows(ConfigMappingException.class, () -> doc.root().index(0));
        YamlDocument yaml = YamlDocument.parse("test.yml", "literal.dot: {}\nlist:\n - k.dot: 3\n - null\n");
        assertEquals("literal.dot", yaml.root().child("literal.dot").name());
        assertEquals("list[0].k.dot", yaml.node("list").index(0).child("k.dot").path());
        assertEquals(3, yaml.node("list").index(0).child("k.dot").raw());
        assertTrue(yaml.node("list").index(1).exists());
        assertThrows(ConfigMappingException.class, () -> yaml.root().index(0));
    }

    @Test void creationWritesParentWhileReplacedViewKeepsItsOldNode() {
        ValueDocument doc = document(map("old", map("value", 1)));
        ConfigNode original = doc.node("old"); ConfigNode sibling = doc.root();
        ConfigNode fresh = doc.root().createSection("old");
        assertTrue(fresh.keys().isEmpty()); assertEquals(1, original.child("value").raw());
        assertTrue(sibling.child("old").keys().isEmpty());
        fresh.createSection("new.key");
        assertTrue(sibling.child("old").child("new.key").exists());
        assertEquals("new.key", fresh.child("new.key").name());
        assertThrows(ConfigMappingException.class, () -> original.child("value").createSection("bad"));
    }

    @Test void sectionWriterReceivesActualPathAndSnapshotForEqualValueSections() {
        List<String> paths = new ArrayList<String>();
        ValueDocument doc = ValueDocument.of("same.json", map("left", map("a", 1), "right", map("a", 1)), (path, values) -> {
            paths.add(path); assertThrows(UnsupportedOperationException.class, values::clear);
            return "comment-at-" + path + ":" + values;
        });
        assertEquals("comment-at-left:{a=1}", doc.node("left").sectionText());
        assertEquals("comment-at-right:{a=1}", doc.node("right").sectionText());
        assertTrue(doc.root().sectionText().startsWith("comment-at-:"));
        assertEquals(Arrays.asList("left", "right", ""), paths);
        doc.node("left").createSection("new"); assertTrue(doc.node("left").sectionText().contains("new={}"));
    }

    @Test void yamlSectionTextUsesItsWriterAndRetainsNestedComments() {
        YamlDocument doc = YamlDocument.parse("comments.yml", "outside: 7\nsection:\n  # value comment\n  nested: 1\n");
        String text = doc.node("section").sectionText();
        assertTrue(text.contains("# value comment"), text); assertFalse(text.contains("outside"));
        assertEquals(1, YamlDocument.parse("rendered.yml", text).node("nested").raw());
        doc.node("section").createSection("added"); assertTrue(doc.node("section").sectionText().contains("added"));
        assertThrows(ConfigMappingException.class, () -> doc.node("outside").sectionText());
        assertThrows(ConfigMappingException.class, () -> doc.node("absent").sectionText());
    }

    @Test void nodeWriterDistinguishesReplacementFromOldHeldViewAndEqualContent() {
        List<ConfigNode> initial = new ArrayList<ConfigNode>();
        ValueDocument doc = ValueDocument.ofWithNodeWriter("comments", map("left", map(), "right", map()), (node, values) -> {
            assertThrows(UnsupportedOperationException.class, values::clear);
            return node.sameNode(initial.get(0)) ? "original" : "new";
        });
        ConfigNode old = doc.node("left"); initial.add(old);
        assertEquals("original", old.sectionText()); assertEquals("new", doc.node("right").sectionText());
        ConfigNode fresh = doc.root().createSection("left");
        assertFalse(fresh.sameNode(old)); assertTrue(fresh.sameNode(doc.node("left")));
        assertEquals("new", fresh.sectionText()); assertEquals("original", old.sectionText());
        assertFalse(old.sameNode(document(map("left", map())).node("left")));
    }

    @Test void nodeIdentityHandlesSequencesNullMissingScalarsAndAmbiguousPaths() {
        ValueDocument doc = document(map("nil", null, "otherNil", null, "a", 1, "b", 1,
                "list", Arrays.asList(map(), null), "list[0]", map(), "list[1]", null,
                "literal.dot", 1, "literal", map("dot", 1)));
        assertTrue(doc.root().sameNode(doc.root())); assertTrue(doc.node("list").sameNode(doc.node("list")));
        assertTrue(doc.node("list").index(0).sameNode(doc.node("list").index(0)));
        assertFalse(doc.node("list").index(0).sameNode(doc.root().child("list[0]")));
        assertTrue(doc.node("nil").sameNode(doc.node("nil"))); assertFalse(doc.node("nil").sameNode(doc.node("otherNil")));
        assertTrue(doc.node("a").sameNode(doc.node("a"))); assertFalse(doc.node("a").sameNode(doc.node("b")));
        assertFalse(doc.root().child("literal.dot").sameNode(doc.node("literal.dot")));
        assertFalse(doc.root().child("list[1]").sameNode(doc.node("list").index(1)));
        assertFalse(doc.node("missing").sameNode(doc.node("missing"))); assertFalse(doc.root().sameNode(null));
    }

    @Test void yamlNodeIdentityKeepsOldViewsAndRejectsOtherDocumentsAndMissing() {
        YamlDocument yaml = YamlDocument.parse("identity.yml", "section: {}\nlist: [{}]\nnil: null\n");
        ConfigNode old = yaml.node("section"); assertTrue(old.sameNode(yaml.node("section")));
        assertTrue(yaml.node("list").index(0).sameNode(yaml.node("list").index(0)));
        assertTrue(yaml.node("nil").sameNode(yaml.node("nil")));
        yaml.root().createSection("section"); assertFalse(old.sameNode(yaml.node("section")));
        assertTrue(old.sameNode(old)); assertFalse(old.sameNode(YamlDocument.parse("identity.yml", "section: {}\n").node("section")));
        assertFalse(yaml.node("missing").sameNode(yaml.node("missing")));
        assertFalse(old.sameNode(document(map("section", map())).node("section")));
    }

    @Test void invalidTreesAndWriterFailuresAreExplicit() {
        Map<String, Object> cyclic = new LinkedHashMap<String, Object>(); cyclic.put("self", cyclic);
        assertThrows(ConfigException.class, () -> document(cyclic));
        Map<Object, Object> wrongKeys = new LinkedHashMap<Object, Object>(); wrongKeys.put(7, "bad");
        assertThrows(ConfigException.class, () -> document(map("nested", wrongKeys)));
        ValueDocument failed = ValueDocument.of("failed", map(), (path, values) -> { throw new IllegalStateException("writer failed"); });
        assertEquals("writer failed", assertThrows(IllegalStateException.class, () -> failed.root().sectionText()).getMessage());
        ValueDocument nullWriter = ValueDocument.of("null", map(), (path, values) -> null);
        assertThrows(NullPointerException.class, () -> nullWriter.root().sectionText());
    }

    @Test void mapperHandlesPojoListMapArrayAliasCustomScalarAndNull() {
        LocalDate date = LocalDate.of(2026, 10, 2);
        ValueDocument doc = document(map("item", map("count", 3), "items", Arrays.asList(map("count", 4)),
                "lookup", map("a.dot", map("count", 5)), "numbers", Arrays.asList(1, 2),
                "literal.dot", "alias", "date", date, "nullable", null));
        YamlConfigMapper mapper = new YamlConfigMapper().registerConverter(LocalDate.class, node -> (LocalDate) node.raw());
        Settings settings = mapper.read(doc.root(), Settings.class);
        assertEquals(3, settings.item.count); assertEquals(4, settings.items.get(0).count);
        assertEquals(5, settings.lookup.get("a.dot").count); assertArrayEquals(new int[]{1, 2}, settings.numbers);
        assertEquals("alias", settings.alias); assertSame(date, settings.date); assertNull(settings.nullable);
    }

    @Test void mapperReportsNestedErrorSourcePathAndUnknownKeysOnce() {
        Logger logger = Logger.getLogger(YamlConfigMapper.class.getName()); List<String> messages = new ArrayList<String>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        try {
            YamlConfigMapper mapper = new YamlConfigMapper();
            ValueDocument doc = document(map("items", Arrays.asList(map("count", 4, "typo", 1))));
            mapper.read(doc.root(), Settings.class); mapper.read(doc.root(), Settings.class);
            assertEquals(1, messages.size()); assertTrue(messages.get(0).contains("values:items[0].typo"));
            ConfigMappingException failure = assertThrows(ConfigMappingException.class,
                    () -> mapper.read(document(map("items", Arrays.asList(map("count", "wrong")))).root(), Settings.class));
            assertTrue(failure.getMessage().contains("values:items[0].count"), failure.getMessage());
        } finally { logger.removeHandler(handler); }
    }

    @Test void yamlRawRulesAndMapperKeyLocationsRemainUnchanged() {
        YamlDocument yaml = YamlDocument.parse("unchanged.yml", "quoted: \"null\"\nboolean: yes\n");
        assertEquals("null", yaml.node("quoted").raw()); assertEquals(false, yaml.node("boolean").raw());
        Logger logger = Logger.getLogger(YamlConfigMapper.class.getName()); List<String> messages = new ArrayList<String>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        try {
            new YamlConfigMapper().read(YamlDocument.parse("location.yml", "count: 1\nunknown: true\n").root(), Item.class);
            assertTrue(messages.get(0).contains("location.yml:2:1 (unknown)"), messages.toString());
        } finally { logger.removeHandler(handler); }
    }

    private static ValueDocument document(Map<String, Object> values) { return ValueDocument.of("values", values, (path, map) -> map.toString()); }
    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
        return result;
    }
    static class Item { int count; }
    static class Settings {
        Item item; List<Item> items; Map<String, Item> lookup; int[] numbers;
        @Key("literal.dot") String alias;
        LocalDate date; String nullable = "default";
    }
}
