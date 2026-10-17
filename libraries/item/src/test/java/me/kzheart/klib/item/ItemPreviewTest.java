package me.kzheart.klib.item;

import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItemPreviewTest {
    @Test
    void unsupportedProviderNeverFallsBackToRealGeneration() {
        AtomicInteger generated = new AtomicInteger();
        ExternalItemSource source = new ExternalItemSource() {
            @Override public String prefix() { return "custom"; }
            @Override public String plugin() { return "Custom"; }
            @Override public ItemStack create(String id) {
                generated.incrementAndGet();
                return new ItemStack(Material.DIAMOND);
            }
            @Override public String identify(ItemStack item) { return null; }
        };
        ItemPreview preview = ExternalItems.of(source).preview("custom:gem", null);
        assertEquals(ItemPreview.Status.UNSUPPORTED, preview.status());
        assertFalse(preview.item().isPresent());
        assertEquals(0, generated.get());
    }

    @Test
    void missingProviderDiffersFromUnknownItemAndInvalidReference() {
        assertEquals(ItemPreview.Status.NOT_READY, ExternalItems.empty().preview("zap:gem", null).status());
        assertThrows(IllegalArgumentException.class,
                () -> ExternalItems.empty().preview("unknown:gem", null));
        assertThrows(IllegalArgumentException.class,
                () -> ItemPreview.unavailable(ItemPreview.Status.READY, "invalid"));
        assertThrows(IllegalArgumentException.class, () -> ItemPreview.ready(null));
    }

    @Test
    void vanillaPreviewAndResultAreDefensivelyCopied() {
        FakeItemServer.install(false);
        ItemStack original = new ItemStack(Material.DIAMOND, 2);
        ItemPreview preview = ItemPreview.ready(original);
        original.setAmount(5);
        assertEquals(2, preview.item().get().getAmount());
        assertEquals(Material.DIAMOND, ExternalItems.empty().preview("diamond", null).item().get().getType());
    }

    @Test
    void providerFailuresRemainVisibleWithoutTryingGeneration() {
        IllegalStateException failure = new IllegalStateException("preview failed");
        ExternalItemSource source = new ExternalItemSource() {
            @Override public String prefix() { return "custom"; }
            @Override public String plugin() { return "Custom"; }
            @Override public ItemStack create(String id) { throw new AssertionError("must not generate"); }
            @Override public ItemPreview preview(String id, Player player) { throw failure; }
            @Override public String identify(ItemStack item) { return null; }
        };
        ItemPreview preview = ExternalItems.of(source).preview("custom:gem", null);
        assertEquals(ItemPreview.Status.FAILED, preview.status());
        assertSame(failure, preview.failure().get());
        assertFalse(preview.item().isPresent());
    }
}
