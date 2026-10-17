package me.kzheart.klib.item;

import me.kzheart.klib.item.tag.NbtCompound;
import me.kzheart.klib.item.tag.NbtList;
import me.kzheart.klib.item.tag.NbtTag;
import me.kzheart.klib.item.tag.NbtType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeTagsJavaInteropTest {
    @Test
    void javaUsesOrdinaryConstructorsAndStaticFactories() {
        NbtCompound tags = new NbtCompound()
                .put("owner", NbtTag.string("Alex"))
                .put("level", NbtTag.intValue(7))
                .put("history", NbtList.of(NbtTag.longValue(1L), NbtTag.longValue(2L)))
                .put("proof", NbtTag.bytes(new byte[]{1, 2}));
        assertEquals("Alex", tags.get("owner").value());
        assertEquals(NbtType.INT, tags.get("level").getType());
        assertEquals(2, ((NbtList) tags.get("history")).size());
        assertArrayEquals(new byte[]{1, 2}, (byte[]) tags.get("proof").value());
        assertEquals(tags, tags.copy());
        tags.remove("owner");
        assertFalse(tags.contains("owner"));
    }

    @Test
    void collectionViewsDoNotAllowStructuralMutation() {
        NbtCompound tags = new NbtCompound().put("x", NbtTag.intValue(1));
        assertThrows(UnsupportedOperationException.class, () -> tags.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> tags.keys().clear());
        NbtList list = NbtList.of(NbtTag.string("x"));
        assertThrows(UnsupportedOperationException.class, () -> list.values().clear());
    }
}
