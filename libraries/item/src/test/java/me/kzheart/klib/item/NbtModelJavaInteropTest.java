package me.kzheart.klib.item;

import me.kzheart.klib.item.tag.NbtCompound;
import me.kzheart.klib.item.tag.NbtList;
import me.kzheart.klib.item.tag.NbtTag;
import me.kzheart.klib.item.tag.NbtType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NbtModelJavaInteropTest {
    @Test
    void javaUsesAllTypedAccessorsWithoutKotlinBridges() {
        NbtCompound root = new NbtCompound()
                .putByte("b", (byte) 1).putShort("s", (short) 2).putInt("i", 3).putLong("l", 4L)
                .putFloat("f", 5.5f).putDouble("d", 6.5).putString("text", "hello")
                .putByteArray("bytes", new byte[]{1}).putIntArray("ints", new int[]{2})
                .putLongArray("longs", new long[]{3})
                .putCompound("object", new NbtCompound().putString("name", "Alex"))
                .putList("list", NbtList.of(NbtTag.intValue(7)));
        assertEquals((byte) 1, root.getByte("b").byteValue());
        assertEquals((short) 2, root.getShort("s").shortValue());
        assertEquals(3, root.getInt("i").intValue());
        assertEquals(4L, root.getLong("l").longValue());
        assertEquals(5.5f, root.getFloat("f").floatValue());
        assertEquals(6.5, root.getDouble("d").doubleValue());
        assertEquals("hello", root.getString("text"));
        assertArrayEquals(new byte[]{1}, root.getByteArray("bytes"));
        assertArrayEquals(new int[]{2}, root.getIntArray("ints"));
        assertArrayEquals(new long[]{3}, root.getLongArray("longs"));
        assertEquals("Alex", root.getCompound("object").getString("name"));
        assertEquals(7, root.getList("list").get(0).asInt());
        assertEquals((byte) 1, root.get("b").asByte());
        assertEquals((short) 2, root.get("s").asShort());
        assertEquals(3, root.get("i").asInt());
        assertEquals(4L, root.get("l").asLong());
        assertEquals(5.5f, root.get("f").asFloat());
        assertEquals(6.5, root.get("d").asDouble());
        assertEquals("hello", root.get("text").asString());
        assertArrayEquals(new byte[]{1}, root.get("bytes").asByteArray());
        assertArrayEquals(new int[]{2}, root.get("ints").asIntArray());
        assertArrayEquals(new long[]{3}, root.get("longs").asLongArray());
        assertSame(root.getCompound("object"), root.get("object").asCompound());
        assertSame(root.getList("list"), root.get("list").asList());
        assertEquals(1, root.get("b").asNumber().intValue());
        assertEquals(2L, root.get("s").asNumber().longValue());
        assertEquals(3.0, root.get("i").asNumber().doubleValue());
        assertEquals(4, root.get("l").asNumber().intValue());
        assertEquals(5, root.get("f").asNumber().intValue());
        assertEquals(6, root.get("d").asNumber().intValue());
        assertThrows(IllegalStateException.class, () -> root.get("text").asNumber());
    }

    @Test
    void javaGetsBoxedMissingValuesAndPrimitiveDefaultOverloads() {
        NbtCompound root = new NbtCompound();
        assertNull(root.getByte("x"));
        assertNull(root.getShort("x"));
        assertNull(root.getInt("x"));
        assertNull(root.getLong("x"));
        assertNull(root.getFloat("x"));
        assertNull(root.getDouble("x"));
        assertNull(root.getString("x"));
        assertNull(root.getByteArray("x"));
        assertNull(root.getIntArray("x"));
        assertNull(root.getLongArray("x"));
        assertNull(root.getCompound("x"));
        assertNull(root.getList("x"));
        byte b = root.getByte("x", (byte) 1);
        short s = root.getShort("x", (short) 2);
        int i = root.getInt("x", 3);
        long l = root.getLong("x", 4L);
        float f = root.getFloat("x", 5f);
        double d = root.getDouble("x", 6.0);
        assertEquals((byte) 1, b);
        assertEquals((short) 2, s);
        assertEquals(3, i);
        assertEquals(4L, l);
        assertEquals(5f, f);
        assertEquals(6.0, d);
        assertEquals("fallback", root.getString("x", "fallback"));
        assertArrayEquals(new byte[]{1}, root.getByteArray("x", new byte[]{1}));
        assertArrayEquals(new int[]{2}, root.getIntArray("x", new int[]{2}));
        assertArrayEquals(new long[]{3}, root.getLongArray("x", new long[]{3}));
        NbtCompound compound = new NbtCompound();
        NbtList list = new NbtList();
        assertSame(compound, root.getCompound("x", compound));
        assertSame(list, root.getList("x", list));
        root.putString("x", "123");
        assertThrows(IllegalStateException.class, () -> root.getInt("x"));
        assertThrows(IllegalStateException.class, () -> root.getInt("x", 123));
    }

    @Test
    void javaUsesExplicitBooleanAndUuidHelpers() {
        UUID uuid = UUID.fromString("12345678-90ab-cdef-1234-567890abcdef");
        NbtCompound root = new NbtCompound().putBoolean("active", true).putUuid("id", uuid);
        assertTrue(root.getBoolean("active"));
        assertNull(root.getBoolean("missing"));
        boolean defaultValue = root.getBoolean("missing", false);
        assertFalse(defaultValue);
        assertEquals(uuid, root.getUuid("id"));
        assertSame(uuid, root.getUuid("missing", uuid));
        assertNull(root.getUuid("missing"));
        assertTrue(NbtTag.booleanValue(true).asBoolean());
        assertEquals(uuid, NbtTag.uuidValue(uuid).asUuid());
        assertTrue(NbtTag.byteValue((byte) 2).asBoolean());
        assertTrue(NbtTag.byteValue((byte) -1).asBoolean());
        assertThrows(IllegalArgumentException.class, () -> NbtTag.string("1-1-1-1-1").asUuid());
        assertThrows(IllegalArgumentException.class, () -> NbtTag.from(true));
        assertThrows(IllegalArgumentException.class, () -> NbtTag.from(uuid));
    }

    @Test
    void javaCanRecursivelyConvertMapsAndListsButFromStaysStrict() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("level", (short) 2);
        map.put("rows", Arrays.asList(Collections.singletonMap("name", "a"),
                Collections.singletonMap("name", "b")));
        NbtCompound root = NbtTag.ofMap(map);
        assertEquals(NbtType.SHORT, root.get("level").getType());
        assertEquals("b", root.getPath("rows[1].name").asString());
        NbtList list = NbtTag.ofList(Arrays.asList(1, 2, 3));
        assertEquals(NbtType.INT, list.elementType());
        assertThrows(IllegalArgumentException.class, () -> NbtTag.ofList(Arrays.asList(1, 2L)));
        assertThrows(IllegalArgumentException.class, () -> NbtTag.ofMap(Collections.singletonMap(1, "bad key")));
        assertThrows(IllegalArgumentException.class, () -> NbtTag.ofList(Collections.singletonList(null)));
        assertThrows(IllegalArgumentException.class, () -> NbtTag.from(map));
        assertThrows(IllegalArgumentException.class, () -> NbtTag.from(Arrays.asList(1, 2)));
        assertThrows(NullPointerException.class, () -> NbtTag.from(null));
    }

    @Test
    void javaHasMutableListsWithUnmodifiableStructuralViews() {
        NbtList list = new NbtList().add(NbtTag.intValue(1)).add(NbtTag.intValue(2));
        assertEquals(1, list.set(0, NbtTag.intValue(3)).asInt());
        Iterator<NbtTag> iterator = list.iterator();
        iterator.next();
        assertThrows(UnsupportedOperationException.class, iterator::remove);
        assertThrows(UnsupportedOperationException.class, () -> list.values().set(0, NbtTag.string("bad")));
        assertThrows(UnsupportedOperationException.class, () -> list.values().listIterator().add(NbtTag.string("bad")));
        assertThrows(IllegalArgumentException.class, () -> list.add(NbtTag.longValue(3)));
        assertThrows(IllegalArgumentException.class, () -> list.set(0, NbtTag.string("bad")));
        assertEquals(2, list.removeAt(1).asInt());
        assertEquals(NbtType.INT, list.elementType());
        list.clear();
        assertNull(list.elementType());
        list.add(NbtTag.string("now allowed"));
        assertEquals("now allowed", list.get(0).asString());
    }

    @Test
    void javaUsesExplicitEscapedPathsAndFailuresAreAtomic() {
        NbtCompound root = new NbtCompound().putString("a.b", "literal")
                .putList("rows", NbtList.of(new NbtCompound()));
        assertSame(root, root.putPath("rows[0].a\\.b.value", NbtTag.intValue(7)));
        assertTrue(root.containsPath("rows[0].a\\.b.value"));
        assertEquals(7, root.getPath("rows[0].a\\.b.value").asInt());
        assertEquals("literal", root.getString("a.b"));
        NbtCompound before = root.copy();
        assertThrows(NoSuchElementException.class,
                () -> root.putPath("new.branch.list[0].name", NbtTag.string("bad")));
        assertEquals(before, root);
        assertEquals(7, root.removePath("rows[0].a\\.b.value").asInt());
        assertFalse(root.containsPath("rows[0].a\\.b.value"));
    }
}
