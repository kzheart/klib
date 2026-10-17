package me.kzheart.klib.lang;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class MessagePipelineOrderTest {
    @Test
    void appliesFixedPipelineOrderAndPreservesRichActions() {
        CapturingRouter router = new CapturingRouter();
        MessageCatalog catalog = key -> Optional.of("{prefix}<red>{name} <hover:show_text:'details'><click:run_command:/inspect>%player%</click></hover>");
        PlaceholderApi papi = (recipient, text) -> text.replace("%player%", "Alex");
        MessagePipeline pipeline = new MessagePipeline(catalog, "[klib] ", papi, router);
        Map<String, Object> placeholders = new HashMap<String, Object>();
        placeholders.put("name", "Welcome");
        MessageRecipient recipient = MessageRecipient.of(new RecordingSender(), false);

        RichText rendered = pipeline.send(recipient, "greeting", placeholders);

        assertSame(rendered, router.message);
        assertSame(recipient, router.recipient);
        assertEquals("[klib] Welcome Alex", rendered.plainText());
        RichTextSegment action = rendered.segments().get(rendered.segments().size() - 1);
        assertEquals(MessageColor.RED, action.color());
        assertEquals(TextAction.Type.HOVER_TEXT, action.hover().type());
        assertEquals("details", action.hover().value());
        assertEquals(TextAction.Type.RUN_COMMAND, action.click().type());
        assertEquals("/inspect", action.click().value());
    }

    @Test
    void acceptsLegacyAndMiniMessageSubsetColors() {
        MessagePipeline pipeline = pipeline("&aGreen <red>Red</red> Plain", null, new CapturingRouter());

        RichText rendered = pipeline.send(MessageRecipient.of(new RecordingSender(), false), "color");

        assertEquals(MessageColor.GREEN, rendered.segments().get(0).color());
        assertEquals(MessageColor.RED, rendered.segments().get(1).color());
        assertEquals(MessageColor.GREEN, rendered.segments().get(2).color());
        assertEquals("Green Red Plain", rendered.plainText());
    }

    @Test
    void rendersDeterministicMissingKey() {
        CapturingRouter router = new CapturingRouter();
        MessagePipeline pipeline = new MessagePipeline(key -> Optional.empty(), "[klib] ", null, router);

        RichText rendered = pipeline.send(MessageRecipient.of(new RecordingSender(), false), "unknown.key");

        assertEquals("[missing:unknown.key]", rendered.plainText());
        assertEquals(MessageColor.RED, rendered.segments().get(0).color());
    }

    @Test
    void onlyExpandsReservedPrefixWhereMessageRequestsIt() {
        Map<String, Object> placeholders = Collections.<String, Object>singletonMap("prefix", "unsafe");
        MessagePipeline pipeline = new MessagePipeline(
                key -> Optional.of("before {prefix} after"),
                "<gray>[klib]</gray>",
                null,
                new CapturingRouter());

        RichText rendered = pipeline.send(
                MessageRecipient.of(new RecordingSender(), false),
                "message",
                placeholders);

        assertEquals("before [klib] after", rendered.plainText());
        assertEquals(MessageColor.GRAY, rendered.segments().get(1).color());
    }

    @Test
    void updatesPrefixWithoutRebuildingPipeline() {
        MessagePipeline pipeline = new MessagePipeline(
                key -> Optional.of("{prefix}message"),
                "old ",
                null,
                new CapturingRouter());

        assertEquals("old message", pipeline.send(
                MessageRecipient.of(new RecordingSender(), false), "message").plainText());

        pipeline.updatePrefix("new ");

        assertEquals("new message", pipeline.send(
                MessageRecipient.of(new RecordingSender(), false), "message").plainText());
    }

    @Test
    void catalogRoutePrefixSelectsChannelAndIsNotRendered() {
        CapturingRouter router = new CapturingRouter();
        MessagePipeline pipeline = new MessagePipeline(
                key -> Optional.of("actionbar: &a+1 coin"), "", null, router);

        RichText rendered = pipeline.send(MessageRecipient.of(new RecordingSender(), false), "reward");

        assertEquals(MessageRoute.ACTION_BAR, router.route);
        assertEquals("+1 coin", rendered.plainText());
    }

    @Test
    void leavesExternalPlaceholdersUntouchedWhenPapiIsDisabled() {
        MessagePipeline pipeline = pipeline("Hello %player%", null, new CapturingRouter());

        RichText rendered = pipeline.send(MessageRecipient.of(new RecordingSender(), false), "message");

        assertEquals("Hello %player%", rendered.plainText());
    }

    @Test
    void consoleRouteDropsColorHoverAndClickMetadata() {
        ConsoleCommandSender console = new ConsoleCommandSender();
        MessagePipeline pipeline = new MessagePipeline(
                key -> Optional.of("&c<hover:show_text:'tip'><click:run_command:/run>Danger</click></hover>"),
                "",
                null,
                new DefaultMessageRouter()
        );

        RichText rendered = pipeline.send(MessageRecipient.commandSender(console), "warning", Collections.<String, Object>emptyMap());

        assertEquals("Danger", console.message());
        assertEquals(MessageColor.RED, rendered.segments().get(0).color());
        assertFalse(console.message().contains("\u00a7"));
    }

    @Test
    void playerCommandSenderReceivesLegacyColorFallback() {
        RecordingSender player = new RecordingSender();
        MessagePipeline pipeline = pipeline("&aHello", null, new DefaultMessageRouter());

        pipeline.send(MessageRecipient.commandSender(player), "message");

        assertEquals("\u00a7aHello", player.message());
    }

    private static MessagePipeline pipeline(String value, PlaceholderApi papi, MessageRouter router) {
        return new MessagePipeline(key -> Optional.of(value), "", papi, router);
    }

    private static final class CapturingRouter implements MessageRouter {
        private MessageRecipient recipient;
        private RichText message;
        private MessageRoute route;

        @Override
        public void route(MessageRecipient recipient, RichText message) {
            this.recipient = recipient;
            this.message = message;
            this.route = MessageRoute.CHAT;
        }

        @Override
        public void route(MessageRecipient recipient, MessageRoute route, RichText message) {
            this.recipient = recipient;
            this.route = route;
            this.message = message;
        }
    }

    public static class RecordingSender {
        private String message;

        public void sendMessage(String message) {
            this.message = message;
        }

        String message() {
            return message;
        }
    }

    public static final class ConsoleCommandSender extends RecordingSender {
    }
}
