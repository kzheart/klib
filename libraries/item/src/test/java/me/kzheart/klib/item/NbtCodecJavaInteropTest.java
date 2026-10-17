package me.kzheart.klib.item;

import me.kzheart.klib.item.tag.NbtCodec;
import me.kzheart.klib.item.tag.NbtCompound;
import me.kzheart.klib.item.tag.NbtList;
import me.kzheart.klib.item.tag.NbtTag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

class NbtCodecJavaInteropTest {
    @Test
    void javaEightCallsSharedCodecUsingOrdinaryStaticMethods() throws Exception {
        NbtCompound source = new NbtCompound()
                .put("version", NbtTag.intValue(7))
                .put("literal.key", NbtTag.string("42i"))
                .put("history", NbtList.of(NbtTag.longValue(1L), NbtTag.longValue(Long.MAX_VALUE)))
                .put("payload", NbtTag.bytes(new byte[]{1, 2, 3}));
        byte[] bytes = NbtCodec.toBytes(source);
        String snbt = NbtCodec.toSnbt(source);
        String json = NbtCodec.toJson(source);
        assertEquals(source, NbtCodec.fromBytes(bytes));
        assertEquals(source, NbtCodec.fromSnbt(snbt));
        assertEquals(source, NbtCodec.fromJson(json));
        NbtCompound restored = (NbtCompound) NbtCodec.fromJson(json);
        restored.put("new", NbtTag.intValue(8));
        restored.put("version", NbtTag.intValue(9));
        assertEquals(9, restored.get("version").value());
        restored.remove("version");
        assertFalse(restored.contains("version"));
        assertEquals(7, source.get("version").value());
        assertFalse(source.contains("new"));
        assertEquals("42i", restored.get("literal.key").value());
        assertNull(restored.get("literal"));
        assertTrue(Modifier.isStatic(NbtCodec.class.getMethod("toSnbt", NbtTag.class).getModifiers()));
        assertTrue(Modifier.isStatic(NbtCodec.class.getMethod("fromJson", String.class).getModifiers()));
    }

    @Test
    void standaloneValuesAndFailureRemainNaturalForJava() {
        assertEquals(NbtTag.longValue(Long.MIN_VALUE), NbtCodec.fromJson("\"-9223372036854775808l\""));
        assertEquals(NbtTag.byteValue((byte) 1), NbtCodec.fromSnbt("true"));
        assertThrows(IllegalArgumentException.class, () -> NbtCodec.fromSnbt("128b"));
        assertThrows(IllegalArgumentException.class, () -> NbtCodec.fromJson("{\"x\":\"1i\",\"x\":\"2i\"}"));
    }
}
