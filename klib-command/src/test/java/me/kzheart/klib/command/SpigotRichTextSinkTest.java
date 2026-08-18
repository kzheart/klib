package me.kzheart.klib.command;

import me.kzheart.klib.lang.MessageColor;
import me.kzheart.klib.lang.RichText;
import me.kzheart.klib.lang.RichTextSegment;
import me.kzheart.klib.lang.TextAction;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpigotRichTextSinkTest {
    @Test
    void playerUsesInteractiveDeliveryWhenAdapterAcceptsMessage() {
        AtomicReference<RichText> delivered = new AtomicReference<RichText>();
        SpigotRichTextSink sink = new SpigotRichTextSink((player, text) -> {
            delivered.set(text);
            return true;
        });
        TestSenders.SenderFixture player = TestSenders.player("Alex");
        RichText text = interactiveText();

        sink.send(player.sender(), text);

        assertSame(text, delivered.get());
        assertTrue(player.messages().isEmpty());
    }

    @Test
    void failedInteractiveDeliveryFallsBackToLegacyForPlayer() {
        SpigotRichTextSink sink = new SpigotRichTextSink((player, text) -> false);
        TestSenders.SenderFixture player = TestSenders.player("Alex");

        sink.send(player.sender(), interactiveText());

        assertEquals(
                Collections.singletonList("\u00a7c\u00a7l\u00a7o\u00a7n\u00a7m\u00a7kRun"),
                player.messages());
    }

    @Test
    void consoleReceivesLegacyColorsWithoutTouchingInteractiveAdapter() {
        AtomicBoolean invoked = new AtomicBoolean();
        SpigotRichTextSink sink = new SpigotRichTextSink((player, text) -> {
            invoked.set(true);
            return true;
        });
        TestSenders.SenderFixture console = TestSenders.console();

        sink.send(console.sender(), interactiveText());

        assertFalse(invoked.get());
        assertEquals(
                Collections.singletonList("§c§l§o§n§m§kRun"),
                console.messages());
    }

    @Test
    void productionReflectionAdapterFailsClosedToLegacyOnUnsupportedPlayer() {
        TestSenders.SenderFixture player = TestSenders.player("Alex");

        SpigotRichTextSink.INSTANCE.send(player.sender(), interactiveText());

        assertEquals(
                Collections.singletonList("\u00a7c\u00a7l\u00a7o\u00a7n\u00a7m\u00a7kRun"),
                player.messages());
    }

    @Test
    void runtimeMethodCacheResolvesSuccessfulLookupOnlyOncePerClass() {
        SpigotRichTextSink.RuntimeMethodCache cache =
                new SpigotRichTextSink.RuntimeMethodCache("substring", Integer.TYPE);

        Method first = cache.find(String.class);
        Method second = cache.find(String.class);

        assertNotNull(first);
        assertSame(first, second);
        assertEquals(1, cache.resolutionCount());
    }

    @Test
    void runtimeMethodCacheRetainsMissingLookupPerClass() {
        SpigotRichTextSink.RuntimeMethodCache cache =
                new SpigotRichTextSink.RuntimeMethodCache("missingMethod");

        assertNull(cache.find(String.class));
        assertNull(cache.find(String.class));
        assertEquals(1, cache.resolutionCount());
    }

    private static RichText interactiveText() {
        return new RichText(Collections.singletonList(new RichTextSegment(
                "Run",
                MessageColor.RED,
                true,
                true,
                true,
                true,
                true,
                new TextAction(TextAction.Type.HOVER_TEXT, "tip"),
                new TextAction(TextAction.Type.RUN_COMMAND, "/run"))));
    }
}
