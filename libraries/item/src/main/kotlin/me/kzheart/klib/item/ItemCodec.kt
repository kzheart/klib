package me.kzheart.klib.item

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.configuration.serialization.ConfigurationSerializable
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InvalidClassException
import java.io.ObjectStreamClass
import java.util.Base64
import java.util.function.Function
import java.util.logging.Logger
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** 带版本信息、解码限额和类型白名单的 Bukkit 值传输格式。 */
class ItemCodec private constructor() {
    private enum class ValueKind(val id: Int) { ITEMS(1), LOCATION(2) }

    private class WhitelistedBukkitObjectInputStream(input: InputStream) : BukkitObjectInputStream(input) {
        @Throws(IOException::class, ClassNotFoundException::class)
        override fun resolveClass(descriptor: ObjectStreamClass): Class<*> {
            val resolved = super.resolveClass(descriptor)
            var component = resolved
            while (component.isArray) component = component.componentType
            if (!isAllowed(component)) throw InvalidClassException(resolved.name, "Class is not allowed in item payloads")
            return resolved
        }
        companion object {
        @JvmStatic private fun isAllowed(type: Class<*>): Boolean {
            if (type.isPrimitive || type == Any::class.java || type == String::class.java ||
                type == Boolean::class.javaObjectType || type == Char::class.javaObjectType) return true
            if (Number::class.java.isAssignableFrom(type) && type.name.startsWith("java.lang.")) return true
            if (isSafeContainer(type)) return true
            if (ItemStack::class.java.isAssignableFrom(type) || ConfigurationSerializable::class.java.isAssignableFrom(type)) return true
            val name = type.name
            return name in GUAVA_FORMS || name.startsWith("org.bukkit.util.io.")
        }
        private fun isSafeContainer(type: Class<*>): Boolean {
            if (!Map::class.java.isAssignableFrom(type) && !Collection::class.java.isAssignableFrom(type)) return false
            val name = type.name
            return name in SAFE_CONTAINERS || name.startsWith("java.util.Collections\$Empty") ||
                name.startsWith("java.util.Collections\$Singleton") || name.startsWith("java.util.Collections\$Unmodifiable")
        }
        }
    }

    private class LimitedInputStream(private val delegate: InputStream, private val limit: Long) : InputStream() {
        private var consumed = 0L
        @Throws(IOException::class) override fun read(): Int = delegate.read().also { if (it >= 0) count(1) }
        @Throws(IOException::class) override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length).also { if (it > 0) count(it.toLong()) }
        @Throws(IOException::class) override fun close() = delegate.close()
        private fun count(amount: Long) {
            consumed += amount
            if (consumed > limit) throw IOException("Decoded Bukkit value exceeds the size limit")
        }
    }

    companion object {
        private const val MAGIC = 0x4b4c4931
        private const val MAGIC_V2 = 0x4b4c4932
        private const val UNKNOWN_DATA_VERSION = -1
        private const val MAX_ITEMS = 4096
        internal const val MAX_ENCODED_CHARS = 8 * 1024 * 1024
        internal const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024
        private val LOGGER = Logger.getLogger(ItemCodec::class.java.name)
        @Volatile private var cachedDataVersion: Int? = null
        private val GUAVA_FORMS = setOf("com.google.common.collect.ImmutableMap\$SerializedForm",
            "com.google.common.collect.ImmutableList\$SerializedForm", "com.google.common.collect.ImmutableSet\$SerializedForm")
        private val SAFE_CONTAINERS = setOf("java.util.HashMap", "java.util.LinkedHashMap", "java.util.ArrayList",
            "java.util.LinkedList", "java.util.HashSet", "java.util.LinkedHashSet", "java.util.Arrays\$ArrayList")

        @JvmStatic fun encode(item: ItemStack): String = encode(item, false)
        @JvmStatic fun encode(item: ItemStack, gzip: Boolean): String = encodeItems(arrayOf(item), gzip)
        @JvmStatic fun decodeItem(value: String): ItemStack {
            val items = decodeItems(value)
            require(items.size == 1 && items[0] != null) { "Encoded value does not contain one item" }
            return items[0]!!
        }
        @JvmStatic fun encodeItems(items: Array<out ItemStack?>, gzip: Boolean): String {
            require(items.size <= MAX_ITEMS) { "Too many items: ${items.size}" }
            return encodePayload(gzip) { output ->
                output.writeByte(ValueKind.ITEMS.id)
                output.writeInt(items.size)
                for (item in items) output.writeObject(item?.clone())
            }
        }
        @JvmStatic fun decodeItems(value: String): Array<ItemStack?> = decodePayload(value, ValueKind.ITEMS) { input ->
            val length = input.readInt()
            if (length < 0 || length > MAX_ITEMS) throw IOException("Invalid item count: $length")
            arrayOfNulls<ItemStack>(length).also { result ->
                for (index in result.indices) {
                    val item = try { input.readObject() } catch (failure: ClassNotFoundException) { throw IOException("Unknown item type", failure) }
                    if (item != null && item !is ItemStack) throw IOException("Unexpected item payload: ${item.javaClass.name}")
                    result[index] = item?.clone()
                }
            }
        }
        @JvmStatic fun encodeInventory(inventory: Inventory, gzip: Boolean): String = encodeItems(inventory.contents, gzip)
        @JvmStatic fun decodeInventory(value: String, destination: Inventory) {
            val items = decodeItems(value)
            require(items.size == destination.size) { "Inventory size mismatch: payload=${items.size}, destination=${destination.size}" }
            destination.contents = items
        }
        @JvmStatic fun encodeLocation(location: Location, gzip: Boolean): String = encodePayload(gzip) { output ->
            output.writeByte(ValueKind.LOCATION.id)
            output.writeUTF(location.world?.name ?: "")
            output.writeDouble(location.x); output.writeDouble(location.y); output.writeDouble(location.z)
            output.writeFloat(location.yaw); output.writeFloat(location.pitch)
        }
        @JvmStatic fun decodeLocation(value: String): Location = decodeLocation(value, Function { Bukkit.getWorld(it) })
        @JvmStatic fun decodeLocation(value: String, worlds: Function<String, World?>): Location = decodePayload(value, ValueKind.LOCATION) { input ->
            val worldName = input.readUTF()
            val world = if (worldName.isEmpty()) null else worlds.apply(worldName)
            if (worldName.isNotEmpty() && world == null) throw IOException("World is not loaded: $worldName")
            Location(world, input.readDouble(), input.readDouble(), input.readDouble(), input.readFloat(), input.readFloat())
        }

        private fun encodePayload(gzip: Boolean, writer: (BukkitObjectOutputStream) -> Unit): String = try {
            val result = ByteArrayOutputStream()
            val envelope = DataOutputStream(result)
            envelope.writeInt(MAGIC_V2); envelope.writeInt(currentDataVersion()); envelope.writeBoolean(gzip)
            BukkitObjectOutputStream(if (gzip) GZIPOutputStream(result) else result).use(writer)
            Base64.getEncoder().encodeToString(result.toByteArray())
        } catch (failure: IOException) { throw IllegalStateException("Cannot encode Bukkit value", failure) }

        private fun <T> decodePayload(encoded: String, expected: ValueKind, reader: (BukkitObjectInputStream) -> T): T {
            require(encoded.length <= MAX_ENCODED_CHARS) { "Encoded Bukkit value exceeds the size limit" }
            try {
                val source = ByteArrayInputStream(Base64.getDecoder().decode(encoded))
                val envelope = DataInputStream(source)
                when (envelope.readInt()) {
                    MAGIC_V2 -> warnOnDataVersionMismatch(envelope.readInt())
                    MAGIC -> Unit
                    else -> throw IOException("Unsupported item payload header")
                }
                val decoded = if (envelope.readBoolean()) GZIPInputStream(source) else source
                return WhitelistedBukkitObjectInputStream(LimitedInputStream(decoded, MAX_PAYLOAD_BYTES.toLong())).use { input ->
                    val kind = input.readUnsignedByte()
                    if (kind != expected.id) throw IOException("Unexpected payload kind: $kind")
                    val result = reader(input)
                    if (input.read() != -1) throw IOException("Trailing item payload data")
                    result
                }
            } catch (failure: EOFException) { throw IllegalArgumentException("Truncated item payload", failure) }
            catch (failure: IOException) { throw IllegalArgumentException("Cannot decode Bukkit value", failure) }
        }
        private fun warnOnDataVersionMismatch(encodedVersion: Int) {
            val current = currentDataVersion()
            if (encodedVersion != UNKNOWN_DATA_VERSION && current != UNKNOWN_DATA_VERSION && encodedVersion != current)
                LOGGER.warning("Decoding item payload written with data version $encodedVersion on a server with data version $current; Minecraft may upgrade or reject the contained items")
        }
        private fun currentDataVersion(): Int {
            cachedDataVersion?.let { return it }
            val resolved = try {
                val unsafe = Bukkit::class.java.getMethod("getUnsafe").invoke(null)
                (unsafe.javaClass.getMethod("getDataVersion").invoke(unsafe) as Number).toInt()
            } catch (_: ReflectiveOperationException) { UNKNOWN_DATA_VERSION }
            catch (_: RuntimeException) { UNKNOWN_DATA_VERSION }
            catch (_: LinkageError) { UNKNOWN_DATA_VERSION }
            cachedDataVersion = resolved
            return resolved
        }
    }
}
