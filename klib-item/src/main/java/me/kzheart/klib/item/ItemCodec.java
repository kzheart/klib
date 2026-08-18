package me.kzheart.klib.item;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.ObjectStreamClass;
import java.io.OutputStream;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** 带版本信息的 Bukkit 物品值 Base64 传输格式。 */
public final class ItemCodec {
    /** 未记录数据版本的旧版头部，仍可解码。 */
    private static final int MAGIC = 0x4b4c4931;
    /** 当前头部：魔数，随后是编码器使用的 Minecraft 数据版本。 */
    private static final int MAGIC_V2 = 0x4b4c4932;
    private static final int UNKNOWN_DATA_VERSION = -1;
    private static final int MAX_ITEMS = 4096;
    static final int MAX_ENCODED_CHARS = 8 * 1024 * 1024;
    static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
    private static final Logger LOGGER = Logger.getLogger(ItemCodec.class.getName());
    private static volatile Integer cachedDataVersion;

    private ItemCodec() {
    }

    public static String encode(ItemStack item) {
        return encode(item, false);
    }

    public static String encode(ItemStack item, boolean gzip) {
        return encodeItems(new ItemStack[]{Objects.requireNonNull(item, "item")}, gzip);
    }

    public static ItemStack decodeItem(String value) {
        ItemStack[] items = decodeItems(value);
        if (items.length != 1 || items[0] == null) {
            throw new IllegalArgumentException("Encoded value does not contain one item");
        }
        return items[0];
    }

    public static String encodeItems(ItemStack[] items, boolean gzip) {
        Objects.requireNonNull(items, "items");
        if (items.length > MAX_ITEMS) {
            throw new IllegalArgumentException("Too many items: " + items.length);
        }
        return encodePayload(gzip, output -> {
            output.writeByte(ValueKind.ITEMS.id);
            output.writeInt(items.length);
            for (ItemStack item : items) {
                output.writeObject(item == null ? null : item.clone());
            }
        });
    }

    public static ItemStack[] decodeItems(String value) {
        return decodePayload(value, ValueKind.ITEMS, input -> {
            int length = input.readInt();
            if (length < 0 || length > MAX_ITEMS) {
                throw new IOException("Invalid item count: " + length);
            }
            ItemStack[] result = new ItemStack[length];
            for (int index = 0; index < length; index++) {
                Object item;
                try {
                    item = input.readObject();
                } catch (ClassNotFoundException exception) {
                    throw new IOException("Unknown item type", exception);
                }
                if (item != null && !(item instanceof ItemStack)) {
                    throw new IOException("Unexpected item payload: " + item.getClass().getName());
                }
                result[index] = item == null ? null : ((ItemStack) item).clone();
            }
            return result;
        });
    }

    public static String encodeInventory(Inventory inventory, boolean gzip) {
        Objects.requireNonNull(inventory, "inventory");
        return encodeItems(inventory.getContents(), gzip);
    }

    public static void decodeInventory(String value, Inventory destination) {
        Objects.requireNonNull(destination, "destination");
        ItemStack[] items = decodeItems(value);
        if (items.length != destination.getSize()) {
            throw new IllegalArgumentException(
                    "Inventory size mismatch: payload=" + items.length + ", destination=" + destination.getSize());
        }
        destination.setContents(items);
    }

    public static String encodeLocation(Location location, boolean gzip) {
        Objects.requireNonNull(location, "location");
        return encodePayload(gzip, output -> {
            output.writeByte(ValueKind.LOCATION.id);
            World world = location.getWorld();
            output.writeUTF(world == null ? "" : world.getName());
            output.writeDouble(location.getX());
            output.writeDouble(location.getY());
            output.writeDouble(location.getZ());
            output.writeFloat(location.getYaw());
            output.writeFloat(location.getPitch());
        });
    }

    public static Location decodeLocation(String value) {
        return decodeLocation(value, Bukkit::getWorld);
    }

    public static Location decodeLocation(String value, Function<String, World> worlds) {
        Objects.requireNonNull(worlds, "worlds");
        return decodePayload(value, ValueKind.LOCATION, input -> {
            String worldName = input.readUTF();
            World world = worldName.isEmpty() ? null : worlds.apply(worldName);
            if (!worldName.isEmpty() && world == null) {
                throw new IOException("World is not loaded: " + worldName);
            }
            return new Location(world, input.readDouble(), input.readDouble(), input.readDouble(),
                    input.readFloat(), input.readFloat());
        });
    }

    private static String encodePayload(boolean gzip, Writer writer) {
        try {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            DataOutputStream envelope = new DataOutputStream(result);
            envelope.writeInt(MAGIC_V2);
            envelope.writeInt(currentDataVersion());
            envelope.writeBoolean(gzip);
            OutputStream payload = gzip ? new GZIPOutputStream(result) : result;
            BukkitObjectOutputStream output = new BukkitObjectOutputStream(payload);
            writer.write(output);
            output.close();
            return Base64.getEncoder().encodeToString(result.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot encode Bukkit value", exception);
        }
    }

    private static <T> T decodePayload(String encoded, ValueKind expected, Reader<T> reader) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length() > MAX_ENCODED_CHARS) {
            throw new IllegalArgumentException("Encoded Bukkit value exceeds the size limit");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            ByteArrayInputStream source = new ByteArrayInputStream(bytes);
            DataInputStream envelope = new DataInputStream(source);
            int magic = envelope.readInt();
            if (magic == MAGIC_V2) {
                warnOnDataVersionMismatch(envelope.readInt());
            } else if (magic != MAGIC) {
                throw new IOException("Unsupported item payload header");
            }
            InputStream decoded = envelope.readBoolean() ? new GZIPInputStream(source) : source;
            InputStream payload = new LimitedInputStream(decoded, MAX_PAYLOAD_BYTES);
            BukkitObjectInputStream input = new WhitelistedBukkitObjectInputStream(payload);
            int kind = input.readUnsignedByte();
            if (kind != expected.id) {
                throw new IOException("Unexpected payload kind: " + kind);
            }
            T result = reader.read(input);
            if (input.read() != -1) {
                throw new IOException("Trailing item payload data");
            }
            input.close();
            return result;
        } catch (EOFException exception) {
            throw new IllegalArgumentException("Truncated item payload", exception);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot decode Bukkit value", exception);
        }
    }

    private static void warnOnDataVersionMismatch(int encodedVersion) {
        int current = currentDataVersion();
        if (encodedVersion != UNKNOWN_DATA_VERSION && current != UNKNOWN_DATA_VERSION && encodedVersion != current) {
            LOGGER.warning("Decoding item payload written with data version " + encodedVersion
                    + " on a server with data version " + current
                    + "; Minecraft may upgrade or reject the contained items");
        }
    }

    /** 使用反射调用，使模块在没有 UnsafeValues#getDataVersion 的服务端上仍能加载。 */
    private static int currentDataVersion() {
        Integer cached = cachedDataVersion;
        if (cached == null) {
            int resolved = UNKNOWN_DATA_VERSION;
            try {
                Object unsafe = Bukkit.class.getMethod("getUnsafe").invoke(null);
                Object version = unsafe.getClass().getMethod("getDataVersion").invoke(unsafe);
                resolved = ((Number) version).intValue();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // 没有正在运行的服务端或 API 版本过旧：将版本记录为未知。
            }
            cached = resolved;
            cachedDataVersion = cached;
        }
        return cached;
    }

    /**
     * 通过白名单限制 Java 反序列化：只能实例化 Bukkit 物品序列化合法产生的类型。
     */
    private static final class WhitelistedBukkitObjectInputStream extends BukkitObjectInputStream {
        private WhitelistedBukkitObjectInputStream(InputStream input) throws IOException {
            super(input);
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass descriptor) throws IOException, ClassNotFoundException {
            Class<?> resolved = super.resolveClass(descriptor);
            Class<?> component = resolved;
            while (component.isArray()) {
                component = component.getComponentType();
            }
            if (!isAllowed(component)) {
                throw new InvalidClassException(resolved.getName(),
                        "Class is not allowed in item payloads");
            }
            return resolved;
        }

        private static boolean isAllowed(Class<?> type) {
            if (type.isPrimitive() || type == Object.class || type == String.class
                    || type == Boolean.class || type == Character.class) {
                return true;
            }
            if (Number.class.isAssignableFrom(type) && type.getName().startsWith("java.lang.")) {
                return true;
            }
            if (isSafeContainer(type)) {
                return true;
            }
            if (ItemStack.class.isAssignableFrom(type) || ConfigurationSerializable.class.isAssignableFrom(type)) {
                return true;
            }
            String name = type.getName();
            // CraftBukkit 1.12 通过 Guava 的无状态不可变集合代理序列化物品元数据。
            // 此输入流仍会分别解析并检查其中的元素。
            if ("com.google.common.collect.ImmutableMap$SerializedForm".equals(name)
                    || "com.google.common.collect.ImmutableList$SerializedForm".equals(name)
                    || "com.google.common.collect.ImmutableSet$SerializedForm".equals(name)) {
                return true;
            }
            // Bukkit 用于 ConfigurationSerializable 值的序列化包装器。
            return name.startsWith("org.bukkit.util.io.");
        }

        private static boolean isSafeContainer(Class<?> type) {
            if (!Map.class.isAssignableFrom(type) && !Collection.class.isAssignableFrom(type)) {
                return false;
            }
            String name = type.getName();
            return "java.util.HashMap".equals(name)
                    || "java.util.LinkedHashMap".equals(name)
                    || "java.util.ArrayList".equals(name)
                    || "java.util.LinkedList".equals(name)
                    || "java.util.HashSet".equals(name)
                    || "java.util.LinkedHashSet".equals(name)
                    || "java.util.Arrays$ArrayList".equals(name)
                    || name.startsWith("java.util.Collections$Empty")
                    || name.startsWith("java.util.Collections$Singleton")
                    || name.startsWith("java.util.Collections$Unmodifiable");
        }
    }

    private interface Writer {
        void write(BukkitObjectOutputStream output) throws IOException;
    }

    private interface Reader<T> {
        T read(BukkitObjectInputStream input) throws IOException;
    }

    private static final class LimitedInputStream extends InputStream {
        private final InputStream delegate;
        private final long limit;
        private long consumed;

        private LimitedInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                count(1L);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        private void count(long amount) throws IOException {
            consumed += amount;
            if (consumed > limit) {
                throw new IOException("Decoded Bukkit value exceeds the size limit");
            }
        }
    }

    private enum ValueKind {
        ITEMS(1),
        LOCATION(2);

        private final int id;

        ValueKind(int id) {
            this.id = id;
        }
    }
}
