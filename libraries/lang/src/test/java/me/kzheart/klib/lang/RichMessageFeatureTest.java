package me.kzheart.klib.lang;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RichMessageFeatureTest {
    @Test
    void legacyOutputResetsAcrossSegments() {
        RichText message = render("<red>Red</red> Plain");

        assertEquals("\u00a7cRed\u00a7r Plain", message.legacyText());
    }

    @Test
    void legacyColorCodeResetsPriorDecorations() {
        RichText message = render("&lBold&cRed");

        assertTrue(message.segments().get(0).bold());
        assertFalse(message.segments().get(1).bold());
        assertEquals("\u00a7lBold\u00a7r\u00a7cRed", message.legacyText());
    }

    @Test
    void parsesHexFormsAndDegradesToNearestLegacyColor() {
        RichText message = render("&#FF0000Red <#54FE54>Green</#54FE54>");

        assertEquals(0xFF0000, message.segments().get(0).color().rgb());
        assertFalse(message.segments().get(0).color().isLegacy());
        assertEquals(MessageColor.DARK_RED, message.segments().get(0).color().nearestLegacy());
        assertEquals(MessageColor.GREEN, message.segments().get(1).color().nearestLegacy());
        assertTrue(message.legacyText().startsWith("\u00a74Red"));
    }

    @Test
    void nestedStylesRestoreParentState() {
        RichText message = render("<red>A<bold>B<blue>C</blue>D</bold>E</red>F");

        assertEquals(MessageColor.RED, message.segments().get(0).color());
        assertEquals(MessageColor.RED, message.segments().get(1).color());
        assertTrue(message.segments().get(1).bold());
        assertEquals(MessageColor.BLUE, message.segments().get(2).color());
        assertTrue(message.segments().get(2).bold());
        assertEquals(MessageColor.RED, message.segments().get(3).color());
        assertTrue(message.segments().get(3).bold());
        assertEquals(MessageColor.RED, message.segments().get(4).color());
        assertFalse(message.segments().get(4).bold());
        assertEquals(null, message.segments().get(5).color());
    }

    @Test
    void supportsOfficialMiniMessageGradientRainbowEscapingAndSemanticTags() {
        RichText message = render("<gradient:#ff0000:#0000ff>AB</gradient> "
                + "<rainbow>CD</rainbow> \\<red> <lang:chat.type.text:'Alex':'Hi'> <key:key.jump>");

        assertEquals("AB CD <red> chat.type.textAlexHi key.jump", message.plainText());
        assertNotEquals(message.segments().get(0).color(), message.segments().get(1).color());
    }

    @Test
    void dispatchesEveryRouteWithoutAdventureTypes() {
        RouteCapturingRouter router = new RouteCapturingRouter();
        MessagePipeline pipeline = pipeline(new MapMessageCatalog(Collections.<String, Object>singletonMap("key", "hello")), router);
        MessageRecipient recipient = MessageRecipient.of(new Object(), false);

        for (MessageRoute route : MessageRoute.values()) {
            RichText result = pipeline.send(recipient, route, "key");
            assertSame(result, router.message);
            assertEquals(route, router.route);
        }
    }

    @Test
    void mapAndYamlCatalogSupportStringListAndPrebuiltRichText() {
        RichText prebuilt = RichText.plain("built {value}");
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("string", "one");
        values.put("list", Arrays.asList("two", "three"));
        values.put("rich", prebuilt);
        MessageCatalog catalog = YamlMessageCatalogLoader.fromDecodedMap(values);
        MessagePipeline pipeline = pipeline(catalog, new RouteCapturingRouter());
        Map<String, Object> placeholders = Collections.<String, Object>singletonMap("value", "ok");
        MessageRecipient recipient = MessageRecipient.of(new Object(), false);

        assertEquals("one", pipeline.send(recipient, "string").plainText());
        assertEquals("two\nthree", pipeline.send(recipient, "list").plainText());
        assertEquals("built ok", pipeline.send(recipient, "rich", placeholders).plainText());
        assertTrue(BuiltinMessages.catalog().find(BuiltinMessages.NO_PERMISSION).isPresent());
    }

    @Test
    void yamlCatalogFlattensNestedSectionsToDottedKeys() {
        Map<String, Object> common = new LinkedHashMap<String, Object>();
        common.put("no-permission", "denied");
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("common", common);

        MessageCatalog catalog = YamlMessageCatalogLoader.fromDecodedMap(root);

        assertEquals("denied", catalog.find("common.no-permission").get());
    }

    private static RichText render(String value) {
        return pipeline(key -> Optional.of(value), new RouteCapturingRouter())
                .send(MessageRecipient.of(new Object(), false), "key");
    }

    private static MessagePipeline pipeline(MessageCatalog catalog, MessageRouter router) {
        return new MessagePipeline(catalog, "", null, router);
    }

    private static final class RouteCapturingRouter implements MessageRouter {
        private MessageRoute route;
        private RichText message;

        @Override
        public void route(MessageRecipient recipient, RichText message) {
            this.route = MessageRoute.CHAT;
            this.message = message;
        }

        @Override
        public void route(MessageRecipient recipient, MessageRoute route, RichText message) {
            this.route = route;
            this.message = message;
        }
    }
}
