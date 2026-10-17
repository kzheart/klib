package me.kzheart.klib.lang;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 占位符值均为字面文本：不解析 MiniMessage、旧版颜色码、路由或前缀。 */
class MessagePipelineInjectionTest {
    @Test
    void placeholderValueCannotInjectClickActions() {
        CapturingRouter router = new CapturingRouter();
        MessagePipeline pipeline = pipeline("Hello {name}", router);

        RichText rendered = pipeline.send(
                recipient(),
                "greeting",
                Placeholders.of("name", "<click:run_command:/op me>evil</click>"));

        assertEquals("Hello <click:run_command:/op me>evil</click>", rendered.plainText());
        for (RichTextSegment segment : rendered.segments()) {
            assertNull(segment.click());
            assertNull(segment.hover());
        }
    }

    @Test
    void placeholderValueCannotInjectLegacyColorCodes() {
        MessagePipeline pipeline = pipeline("Name: {name}", new CapturingRouter());

        RichText rendered = pipeline.send(
                recipient(), "message", Placeholders.of("name", "&cRedName"));

        assertEquals("Name: &cRedName", rendered.plainText());
        for (RichTextSegment segment : rendered.segments()) {
            assertNull(segment.color());
        }
    }

    @Test
    void placeholderValueCannotSwitchTheMessageRoute() {
        CapturingRouter router = new CapturingRouter();
        MessagePipeline pipeline = pipeline("{name} joined", router);

        RichText rendered = pipeline.send(
                recipient(), "join", Placeholders.of("name", "actionbar: Sneaky"));

        assertEquals(MessageRoute.CHAT, router.route);
        assertEquals("actionbar: Sneaky joined", rendered.plainText());
    }

    @Test
    void placeholderValueCannotExpandThePrefixToken() {
        MessagePipeline pipeline = new MessagePipeline(
                key -> Optional.of("Hello {name}"), "<gray>[klib]</gray> ", null, new CapturingRouter());

        RichText rendered = pipeline.send(
                recipient(), "greeting", Placeholders.of("name", "{prefix}"));

        assertEquals("Hello {prefix}", rendered.plainText());
    }

    @Test
    void placeholderValueContainingAnotherPlaceholderTokenStaysLiteral() {
        MessagePipeline pipeline = pipeline("{first} {second}", new CapturingRouter());
        Map<String, Object> placeholders = new HashMap<String, Object>();
        placeholders.put("first", "{second}");
        placeholders.put("second", "value");

        RichText rendered = pipeline.send(recipient(), "message", placeholders);

        assertEquals("{second} value", rendered.plainText());
    }

    @Test
    void richTemplatePlaceholderValueStaysLiteral() {
        Map<String, Object> entries = Collections.<String, Object>singletonMap(
                "rich", new RichText(Collections.singletonList(new RichTextSegment(
                        "Hi {name}", MessageColor.GREEN, false, null, null))));
        MessagePipeline pipeline = new MessagePipeline(
                new MapMessageCatalog(entries), "", null, new CapturingRouter());

        RichText rendered = pipeline.send(
                recipient(), "rich", Placeholders.of("name", "<red>evil</red>"));

        assertEquals("Hi <red>evil</red>", rendered.plainText());
        assertEquals(MessageColor.GREEN, rendered.segments().get(0).color());
    }

    @Test
    void sourcePriorityWinsOverTypePriorityAcrossFallbackCatalogs() {
        MessageCatalog primary = new MapMessageCatalog(
                Collections.<String, Object>singletonMap("key", "primary-string"));
        MessageCatalog fallback = new MapMessageCatalog(
                Collections.<String, Object>singletonMap("key", new RichText(
                        Collections.singletonList(RichTextSegment.plain("fallback-rich")))));
        MessagePipeline pipeline = new MessagePipeline(
                new FallbackMessageCatalog(primary, fallback), "", null, new CapturingRouter());

        RichText rendered = pipeline.send(recipient(), "key");

        assertEquals("primary-string", rendered.plainText());
    }

    @Test
    void listEntryPlaceholderValuesStayLiteral() {
        MessageCatalog catalog = new MapMessageCatalog(Collections.<String, Object>singletonMap(
                "lines", Arrays.asList("line one {name}", "line two")));
        MessagePipeline pipeline = new MessagePipeline(catalog, "", null, new CapturingRouter());

        RichText rendered = pipeline.send(
                recipient(), "lines", Placeholders.of("name", "<bold>x</bold>"));

        assertEquals("line one <bold>x</bold>\nline two", rendered.plainText());
    }

    @Test
    void placeholderValueCannotInvokePlaceholderApi() {
        PlaceholderApi papi = (recipient, text) -> text
                .replace("%trusted%", "template-expanded")
                .replace("%admin_secret%", "leaked");
        MessagePipeline pipeline = new MessagePipeline(
                key -> Optional.of("%trusted% {name}"), "", papi, new CapturingRouter());

        RichText rendered = pipeline.send(
                recipient(), "message", Placeholders.of("name", "%admin_secret%"));

        assertEquals("template-expanded %admin_secret%", rendered.plainText());
    }

    private static MessagePipeline pipeline(String template, MessageRouter router) {
        return new MessagePipeline(key -> Optional.of(template), "", null, router);
    }

    private static MessageRecipient recipient() {
        return MessageRecipient.of(new RecordingSender(), false);
    }

    private static final class CapturingRouter implements MessageRouter {
        private MessageRoute route;

        @Override
        public void route(MessageRecipient recipient, RichText message) {
            this.route = MessageRoute.CHAT;
        }

        @Override
        public void route(MessageRecipient recipient, MessageRoute route, RichText message) {
            this.route = route;
        }
    }

    public static class RecordingSender {
        public void sendMessage(String message) {
        }
    }
}
