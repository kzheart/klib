package me.kzheart.klib.item;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.PriorityQueue;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemCodecCrossVersionTest {
    @Test
    void versionedEnvelopePreservesSlotsWithoutLoadingAServer() {
        ItemStack[] items = new ItemStack[]{null, null, null};
        ItemStack[] restored = ItemCodec.decodeItems(ItemCodec.encodeItems(items, true));
        assertEquals(3, restored.length);
        assertNull(restored[0]);
        assertNull(restored[1]);
        assertNull(restored[2]);
    }

    @Test
    void locationAndMalformedInputUseTheSameVersionedEnvelope() {
        Location location = new Location(null, 1.25, 64.0, -8.5, 90.0f, 12.0f);
        Location restored = ItemCodec.decodeLocation(ItemCodec.encodeLocation(location, true), name -> null);
        assertNull(restored.getWorld());
        assertEquals(location.getX(), restored.getX());
        assertEquals(location.getYaw(), restored.getYaw());

        assertThrows(IllegalArgumentException.class,
                () -> ItemCodec.decodeItem(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})));
    }

    @Test
    void rejectsEncodedValuesBeforeAllocatingAnUnboundedPayload() {
        StringBuilder oversized = new StringBuilder(ItemCodec.MAX_ENCODED_CHARS + 1);
        while (oversized.length() <= ItemCodec.MAX_ENCODED_CHARS) {
            oversized.append('A');
        }
        assertThrows(IllegalArgumentException.class,
                () -> ItemCodec.decodeItem(oversized.toString()));
    }

    @Test
    void permitsOnlyTheInertGuavaProxiesUsedByLegacyBukkitItems() throws Exception {
        Class<?> filter = Class.forName(
                "me.kzheart.klib.item.ItemCodec$WhitelistedBukkitObjectInputStream");
        Method allowed = filter.getDeclaredMethod("isAllowed", Class.class);
        allowed.setAccessible(true);

        assertTrue((Boolean) allowed.invoke(null, Object.class));
        assertTrue((Boolean) allowed.invoke(null, Class.forName(
                "com.google.common.collect.ImmutableMap$SerializedForm")));
        assertFalse((Boolean) allowed.invoke(null, PriorityQueue.class));
    }
}
