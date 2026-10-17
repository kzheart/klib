package me.kzheart.klib.data.json

import com.google.gson.GsonBuilder
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import me.kzheart.klib.data.StorageException
import java.io.BufferedWriter
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Reader
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64

/** 内存状态只包含校验后的非空值；反序列化的不可信可空数据不进入事务。 */
internal class JsonState(
    val schemas: MutableMap<String, Int> = LinkedHashMap(),
    val values: MutableMap<String, MutableMap<String, String>> = LinkedHashMap()
) {
    fun copy(): JsonState = JsonState(LinkedHashMap(schemas),
        values.mapValuesTo(LinkedHashMap()) { LinkedHashMap(it.value) })
}

/** 文件原子替换、字节预算和格式校验的单一边界。 */
internal class JsonStorageFile(private val file: Path) {
    fun read(): JsonState {
        if (!Files.exists(file)) return JsonState()
        try {
            if (Files.size(file) > MAX_FILE_BYTES) throw StorageException("JSON storage exceeds $MAX_FILE_BYTES byte limit: $file")
            JsonReader(boundedReader()).use { reader ->
                reader.isLenient = false
                consumeJsonValue(reader, 1)
                if (reader.peek() != JsonToken.END_DOCUMENT) throw IOException("JSON storage contains trailing data")
            }
            return boundedReader().use { reader ->
                val persisted = GSON.fromJson(reader, PersistedState::class.java) ?: PersistedState()
                persisted.normalize().also(::validate)
            }
        } catch (error: IOException) { throw StorageException("Could not read JSON storage $file", error) }
        catch (error: RuntimeException) {
            throw if (error is StorageException) error else StorageException("Could not read JSON storage $file", error)
        }
    }

    fun write(value: JsonState) {
        validate(value)
        var temporary: Path? = null
        try {
            file.parent?.let { Files.createDirectories(it) }
            val directory = file.parent ?: file.toAbsolutePath().parent
                ?: throw IOException("JSON storage path has no parent directory: $file")
            val target = Files.createTempFile(directory, ".${file.fileName}-", ".tmp")
            temporary = target
            FileChannel.open(target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val writer = BufferedWriter(OutputStreamWriter(
                    LimitedOutputStream(Channels.newOutputStream(channel), MAX_FILE_BYTES), StandardCharsets.UTF_8))
                GSON.toJson(value, writer)
                writer.flush()
            }
            try { Files.move(target, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(target, file, StandardCopyOption.REPLACE_EXISTING) }
        } catch (error: IOException) { throw StorageException("Could not persist JSON storage $file", error) }
        catch (error: RuntimeException) {
            throw if (error is StorageException) error else StorageException("Could not persist JSON storage $file", error)
        } finally {
            temporary?.let { try { Files.deleteIfExists(it) } catch (_: IOException) { } }
        }
    }

    private fun boundedReader(): Reader = InputStreamReader(LimitedInputStream(Files.newInputStream(file), MAX_FILE_BYTES), StandardCharsets.UTF_8)

    private class PersistedState {
        var schemas: Map<String?, Int?>? = LinkedHashMap()
        var values: Map<String?, Map<String?, String?>?>? = LinkedHashMap()
        fun normalize(): JsonState {
            val result = JsonState()
            schemas.orEmpty().forEach { (name, version) ->
                val validName = requireName(name, "schemaName")
                result.schemas[validName] = version ?: throw StorageException("JSON storage contains a null schema version")
            }
            values.orEmpty().forEach { (name, stored) ->
                val validName = requireName(name, "namespace")
                val entries = stored ?: throw StorageException("JSON storage contains a null namespace")
                result.values[validName] = entries.map { (key, encoded) ->
                    requireName(key, "key") to (encoded ?: throw StorageException("JSON storage value exceeds $MAX_VALUE_BYTES byte limit"))
                }.toMap(LinkedHashMap())
            }
            return result
        }
    }

    private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int {
            val value = super.read()
            if (value != -1) { count++; checkLimit() }
            return value
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(bytes, offset, length)
            if (read > 0) { count += read; checkLimit() }
            return read
        }
        private fun checkLimit() { if (count > limit) throw IOException("JSON storage exceeds $limit byte limit") }
    }

    private class LimitedOutputStream(output: OutputStream, private val limit: Long) : FilterOutputStream(output) {
        private var count = 0L
        override fun write(value: Int) { ensureCapacity(1); out.write(value); count++ }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            ensureCapacity(length); out.write(bytes, offset, length); count += length
        }
        private fun ensureCapacity(additionalBytes: Int) {
            if (additionalBytes > limit - count) throw IOException("JSON storage exceeds $limit byte limit")
        }
    }

    companion object {
        const val MAX_VALUE_BYTES = 1024 * 1024
        private const val MAX_FILE_BYTES = 8L * 1024L * 1024L
        private const val MAX_JSON_DEPTH = 16
        private const val MAX_NAMESPACES = 128
        private const val MAX_ENTRIES = 4096
        private const val MAX_NAMESPACE_BYTES = 128
        private const val MAX_KEY_BYTES = 512
        private const val MAX_TOTAL_VALUE_BYTES = 4L * 1024L * 1024L
        private const val MAX_SCHEMAS = 256
        private val GSON = GsonBuilder().disableHtmlEscaping().create()

        fun requireName(value: String?, label: String): String {
            require(!value.isNullOrEmpty()) { "$label must not be empty" }
            val maxBytes = if (label == "key") MAX_KEY_BYTES else MAX_NAMESPACE_BYTES
            require(value.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) { "$label exceeds $maxBytes UTF-8 byte limit" }
            return value
        }

        private fun validate(state: JsonState) {
            if (state.schemas.size > MAX_SCHEMAS) throw StorageException("JSON storage exceeds $MAX_SCHEMAS schema limit")
            state.schemas.keys.forEach { requireName(it, "schemaName") }
            if (state.values.size > MAX_NAMESPACES) throw StorageException("JSON storage exceeds $MAX_NAMESPACES namespace limit")
            var entries = 0
            var valueBytes = 0L
            state.values.forEach { (namespace, stored) ->
                requireName(namespace, "namespace")
                entries += stored.size
                if (entries > MAX_ENTRIES) throw StorageException("JSON storage exceeds $MAX_ENTRIES entry limit")
                stored.forEach { (key, encoded) ->
                    requireName(key, "key")
                    if (encoded.length > ((MAX_VALUE_BYTES + 2) / 3) * 4) throw StorageException("JSON storage value exceeds $MAX_VALUE_BYTES byte limit")
                    val decoded = try { Base64.getDecoder().decode(encoded) }
                    catch (error: IllegalArgumentException) { throw StorageException("JSON storage contains invalid Base64 value", error) }
                    if (decoded.size > MAX_VALUE_BYTES) throw StorageException("JSON storage value exceeds $MAX_VALUE_BYTES byte limit")
                    valueBytes += decoded.size
                    if (valueBytes > MAX_TOTAL_VALUE_BYTES) throw StorageException("JSON storage exceeds $MAX_TOTAL_VALUE_BYTES total value byte limit")
                }
            }
        }

        private fun consumeJsonValue(reader: JsonReader, depth: Int) {
            if (depth > MAX_JSON_DEPTH) throw IOException("JSON storage exceeds nesting depth $MAX_JSON_DEPTH")
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    while (reader.hasNext()) { reader.nextName(); consumeJsonValue(reader, depth + 1) }
                    reader.endObject()
                }
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    while (reader.hasNext()) consumeJsonValue(reader, depth + 1)
                    reader.endArray()
                }
                else -> reader.skipValue()
            }
        }
    }
}
