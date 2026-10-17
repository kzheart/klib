package me.kzheart.klib.remote

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.*
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.EnumSet
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

/** 单工作线程拥有的优先级队列，格式编解码和文件系统安全边界各自封装。 */
class RemoteDiskQueue @Throws(IOException::class) constructor(
    requested: Path, private val maxBytes: Long, private val maxEntries: Int,
    private val maxEventBytes: Int, private val ttlMillis: Long, queueIdentity: ByteArray?
) : AutoCloseable {
    private val directory: Path
    private val quarantine: Path
    private val maxQuarantineBytes = maxOf(1L, minOf(1024L * 1024L, maxBytes / 16L))
    private val maxQuarantineEntries = maxOf(1, minOf(64, maxEntries / 16))
    private val lockChannel: FileChannel
    private val directoryLock: FileLock
    private val directoryKey: Any
    private val incidents = ArrayDeque<Entry>()
    private val logs = ArrayDeque<Entry>()
    private var bytes = 0L
    private var nextSequence = 0L
    @Volatile private var entryCount = 0
    private var quarantineBytes = 0L
    private var quarantineEntries = 0

    init {
        val normalized = requested.toAbsolutePath().normalize()
        QueueFiles.prepareDirectory(normalized)
        directory = normalized.toRealPath()
        quarantine = directory.resolve("quarantine")
        QueueFiles.verifyOwner(directory)
        restrictPermissions(directory, true)
        QueueFiles.verifyAncestors(directory)
        QueueFiles.prepareDirectory(quarantine)
        directoryKey = Files.readAttributes(directory, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
            ?: throw IOException("Remote queue filesystem cannot bind directory identity")
        val lockPath = directory.resolve(".queue.lock")
        lockChannel = FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS)
        restrictPermissions(lockPath, false)
        val acquired = try { lockChannel.tryLock() } catch (failure: Exception) {
            if (failure !is IOException && failure !is RuntimeException) throw failure
            lockChannel.close()
            throw failure
        }
        if (acquired == null) {
            lockChannel.close()
            throw IOException("Remote queue directory is already in use")
        }
        directoryLock = acquired
        try {
            if (!directoryBindingIntact()) throw IOException("Remote queue directory changed during initialization")
            bindIdentity(queueIdentity)
            val files = quarantineFiles()
            quarantineEntries = files.size
            quarantineBytes = files.sumOf { it.bytes }
            pruneQuarantine(0L, 0)
            recover(System.currentTimeMillis())
        } catch (failure: Exception) {
            if (failure !is IOException && failure !is RuntimeException) throw failure
            close()
            throw failure
        }
    }

    private fun bindIdentity(identity: ByteArray?) {
        if (identity == null || identity.size != 32) throw IOException("Remote queue identity is invalid")
        val alphabet = "0123456789abcdef"
        val expected = buildString(identity.size * 2) {
            for (byte in identity) {
                val value = byte.toInt() and 0xff
                append(alphabet[value ushr 4]).append(alphabet[value and 0x0f])
            }
        }.toByteArray(StandardCharsets.US_ASCII)
        val marker = directory.resolve("queue.identity")
        if (Files.exists(marker, NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(marker, NOFOLLOW_LINKS) || Files.size(marker) != expected.size.toLong()) {
                throw IOException("Remote queue identity file is invalid")
            }
            val actual = ByteBuffer.allocate(expected.size)
            FileChannel.open(marker, READ, NOFOLLOW_LINKS).use { channel ->
                while (actual.hasRemaining()) if (channel.read(actual) < 0) throw EOFException("truncated identity")
            }
            if (!MessageDigest.isEqual(expected, actual.array())) throw IOException("Remote queue belongs to a different endpoint or key")
            return
        }
        val temporary = directory.resolve("queue.identity.${UUID.randomUUID()}$TEMP_SUFFIX")
        try {
            FileChannel.open(temporary, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                val data = ByteBuffer.wrap(expected)
                while (data.hasRemaining()) channel.write(data)
            }
            restrictPermissions(temporary, false)
            Files.move(temporary, marker, ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temporary) }
    }

    fun store(incident: Boolean, createdAtMillis: Long, eventId: String,
        envelope: RemoteBatchEnvelope, data: ByteArray): StoreResult {
        if (!directoryBindingIntact()) return StoreResult(false, 0)
        val installationId = envelope.installationId()
        val environment = envelope.environment()
        val encodedEventId = eventId.toByteArray(StandardCharsets.UTF_8)
        val fileBytes = QueueRecord.HEADER_BYTES.toLong() + installationId.size + environment.size + encodedEventId.size + data.size
        if (data.size > maxEventBytes || installationId.size > QueueRecord.MAX_ENVELOPE_FIELD_BYTES ||
            environment.size > QueueRecord.MAX_ENVELOPE_FIELD_BYTES || encodedEventId.size !in 1..QueueRecord.MAX_EVENT_ID_BYTES ||
            fileBytes > maxBytes) return StoreResult(false, 0)
        val evicted = makeRoom(incident, fileBytes, 1)
        if (bytes + quarantineBytes + fileBytes > maxBytes || size() >= maxEntries) return StoreResult(false, evicted)
        val sequence = ++nextSequence
        val stem = String.format(Locale.ROOT, "%019d-%019d-%s", createdAtMillis, sequence, UUID.randomUUID().toString())
        val temporary = directory.resolve(stem + TEMP_SUFFIX)
        val target = directory.resolve(stem + ENTRY_SUFFIX)
        try {
            if (!directoryBindingIntact()) return StoreResult(false, evicted)
            QueueRecord.write(temporary, incident, createdAtMillis, sequence, installationId, environment, encodedEventId, data)
            if (!directoryBindingIntact()) return StoreResult(false, evicted)
            Files.move(temporary, target, ATOMIC_MOVE)
            val entry = Entry(target, incident, createdAtMillis, sequence, eventId, envelope, data.copyOf(), fileBytes)
            entries(incident).addLast(entry)
            bytes += fileBytes
            entryCount++
            return StoreResult(true, evicted)
        } catch (_: IOException) {
            try { if (directoryBindingIntact()) Files.deleteIfExists(temporary) } catch (_: IOException) { }
            return StoreResult(false, evicted)
        }
    }

    fun batch(maxEvents: Int, maxBytes: Int): List<Entry> {
        val result = ArrayList<Entry>()
        if (!directoryBindingIntact()) return result
        val first = incidents.peekFirst() ?: logs.peekFirst() ?: return result
        val used = append(result, incidents, first.envelope(), maxEvents, maxBytes, 0)
        append(result, logs, first.envelope(), maxEvents, maxBytes, used)
        return result
    }

    fun remove(entry: Entry): Boolean {
        if (!directoryBindingIntact()) return false
        val source = entries(entry.incident)
        if (!source.remove(entry)) return true
        bytes -= entry.fileBytes
        entryCount--
        return try { Files.deleteIfExists(entry.path); true } catch (_: IOException) {
            source.addFirst(entry)
            bytes += entry.fileBytes
            entryCount++
            false
        }
    }

    fun purgeExpired(nowMillis: Long): Int = discard(incidents) { expired(it, nowMillis) } + discard(logs) { expired(it, nowMillis) }
    fun discardOversized(maxEventBytes: Int): Int = discard(incidents) { it.data().size > maxEventBytes } +
        discard(logs) { it.data().size > maxEventBytes }
    fun size(): Int = entryCount

    override fun close() {
        try { directoryLock.release() } catch (_: IOException) { }
        try { lockChannel.close() } catch (_: IOException) { }
    }

    private fun recover(nowMillis: Long) {
        val recovered = ArrayList<Entry>()
        Files.newDirectoryStream(directory).use { files ->
            for (path in files) {
                val name = path.fileName.toString()
                if (name == "quarantine") continue
                if (!name.endsWith(ENTRY_SUFFIX)) {
                    if (name.endsWith(TEMP_SUFFIX)) quarantine(path)
                    continue
                }
                val entry = read(path) ?: continue
                if (expired(entry, nowMillis)) {
                    try { Files.deleteIfExists(path) } catch (_: IOException) { quarantine(path) }
                } else recovered.add(entry)
            }
        }
        recovered.sortWith(compareBy<Entry> { it.sequence }.thenBy { it.path.fileName.toString() })
        for (entry in recovered) {
            if (entry.fileBytes > maxBytes) { quarantine(entry.path); continue }
            makeRoom(entry.incident, entry.fileBytes, 1)
            if (bytes + quarantineBytes + entry.fileBytes > maxBytes || size() >= maxEntries) { quarantine(entry.path); continue }
            entries(entry.incident).addLast(entry)
            bytes += entry.fileBytes
            entryCount++
            nextSequence = maxOf(nextSequence, entry.sequence)
        }
    }

    private fun read(path: Path): Entry? = try { QueueRecord.read(path, maxEventBytes) }
        catch (failure: Exception) {
            if (failure !is IOException && failure !is RuntimeException) throw failure
            quarantine(path)
            null
        }

    private fun makeRoom(incident: Boolean, requiredBytes: Long, requiredEntries: Int): Int {
        var evicted = 0
        while (bytes + quarantineBytes + requiredBytes > maxBytes || size() + requiredEntries > maxEntries) {
            val victim = logs.peekFirst() ?: (if (incident) incidents.peekFirst() else null) ?: break
            if (!remove(victim)) break
            evicted++
        }
        return evicted
    }

    private fun append(result: MutableList<Entry>, source: Iterable<Entry>, envelope: RemoteBatchEnvelope,
        maxEvents: Int, maxBatchBytes: Int, alreadyUsed: Int): Int {
        var used = alreadyUsed
        for (entry in source) {
            if (result.size >= maxEvents) break
            if (!entry.envelope().sameAs(envelope)) continue
            if (entry.data().size > maxBatchBytes || used + entry.data().size > maxBatchBytes) break
            result.add(entry)
            used += entry.data().size
        }
        return used
    }

    private inline fun discard(source: Collection<Entry>, predicate: (Entry) -> Boolean): Int {
        var removed = 0
        for (entry in source.toList()) if (predicate(entry)) {
            if (!remove(entry)) break
            removed++
        }
        return removed
    }
    private fun expired(entry: Entry, nowMillis: Long): Boolean = nowMillis - entry.createdAtMillis >= ttlMillis
    private fun entries(incident: Boolean): ArrayDeque<Entry> = if (incident) incidents else logs

    private fun quarantine(path: Path) {
        try {
            if (path.normalize().parent != directory) return
            val fileBytes = if (Files.isRegularFile(path, NOFOLLOW_LINKS)) Files.size(path) else 0L
            if (fileBytes > maxQuarantineBytes) { Files.deleteIfExists(path); return }
            pruneQuarantine(fileBytes, 1)
            if (quarantineBytes + fileBytes > maxQuarantineBytes || quarantineEntries >= maxQuarantineEntries) {
                Files.deleteIfExists(path); return
            }
            Files.move(path, quarantine.resolve("${path.fileName}.corrupt-${UUID.randomUUID()}"), ATOMIC_MOVE)
            quarantineBytes += fileBytes
            quarantineEntries++
        } catch (_: AtomicMoveNotSupportedException) {
            try {
                val target = quarantine.resolve("${path.fileName}.corrupt-${UUID.randomUUID()}")
                Files.move(path, target)
                quarantineBytes += if (Files.isRegularFile(target, NOFOLLOW_LINKS)) Files.size(target) else 0L
                quarantineEntries++
            } catch (_: IOException) { }
        } catch (failure: Exception) {
            if (failure !is IOException && failure !is RuntimeException) throw failure
        }
    }

    private fun pruneQuarantine(requiredBytes: Long, requiredEntries: Int) {
        try {
            for (file in quarantineFiles()) {
                if (quarantineBytes + requiredBytes <= maxQuarantineBytes && quarantineEntries + requiredEntries <= maxQuarantineEntries) break
                try {
                    if (Files.deleteIfExists(file.path)) { quarantineBytes -= file.bytes; quarantineEntries-- }
                } catch (_: IOException) { break }
            }
        } catch (_: IOException) { }
    }

    private fun quarantineFiles(): List<Quarantined> {
        val files = ArrayList<Quarantined>()
        Files.newDirectoryStream(quarantine).use { stream ->
            for (path in stream) {
                if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) { Files.deleteIfExists(path); continue }
                files.add(Quarantined(path, Files.size(path), Files.getLastModifiedTime(path, NOFOLLOW_LINKS).toMillis()))
            }
        }
        return files.sortedWith(compareBy<Quarantined> { it.modifiedAt }.thenBy { it.path.fileName.toString() })
    }

    private fun directoryBindingIntact(): Boolean = try {
        QueueFiles.verifyAncestors(directory)
        val current = Files.readAttributes(directory, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        current.isDirectory && directoryKey == current.fileKey()
    } catch (failure: Exception) {
        if (failure !is IOException && failure !is RuntimeException) throw failure
        false
    }

    class Entry internal constructor(internal val path: Path, internal val incident: Boolean,
        internal val createdAtMillis: Long, internal val sequence: Long, private val eventId: String,
        private val envelope: RemoteBatchEnvelope, private val data: ByteArray, internal val fileBytes: Long) {
        fun data(): ByteArray = data
        fun eventId(): String = eventId
        fun envelope(): RemoteBatchEnvelope = envelope
    }
    class StoreResult internal constructor(private val stored: Boolean, private val evicted: Int) {
        fun stored(): Boolean = stored
        fun evicted(): Int = evicted
    }
    private class Quarantined(val path: Path, val bytes: Long, val modifiedAt: Long)

    companion object {
        private const val ENTRY_SUFFIX = ".rqe"
        private const val TEMP_SUFFIX = ".tmp"
        @JvmStatic @Throws(IOException::class)
        fun restrictPermissions(path: Path, directory: Boolean) = QueueFiles.restrictPermissions(path, directory)
    }
}

/** 二进制布局与 CRC 同时绑定元数据和正文，不信任恢复目录中的记录。 */
private object QueueRecord {
    private const val MAGIC = 0x4b525131
    private const val VERSION: Byte = 1
    const val HEADER_BYTES = 4 + 1 + 1 + 8 + 8 + 4 + 4 + 4 + 4 + 8
    const val MAX_ENVELOPE_FIELD_BYTES = 8 * 1024
    const val MAX_EVENT_ID_BYTES = 1024

    fun read(path: Path, maxEventBytes: Int): RemoteDiskQueue.Entry {
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw IOException("invalid queue entry file")
        val fileBytes = Files.size(path)
        if (fileBytes < HEADER_BYTES || fileBytes > HEADER_BYTES.toLong() + maxEventBytes + MAX_ENVELOPE_FIELD_BYTES * 2L + MAX_EVENT_ID_BYTES) {
            throw IOException("invalid queue entry size")
        }
        val buffer = ByteBuffer.allocate(fileBytes.toInt())
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw EOFException("truncated queue entry")
        }
        buffer.flip()
        if (buffer.int != MAGIC || buffer.get() != VERSION) throw IOException("invalid queue entry header")
        val priority = buffer.get().toInt()
        if (priority !in 0..1) throw IOException("invalid priority")
        val createdAtMillis = buffer.long
        if (createdAtMillis < 0L || createdAtMillis > System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5L)) {
            throw IOException("invalid queue entry timestamp")
        }
        val sequence = buffer.long
        if (sequence < 1L) throw IOException("invalid queue entry sequence")
        val installationLength = buffer.int
        val environmentLength = buffer.int
        val eventIdLength = buffer.int
        val length = buffer.int
        val expectedCrc = buffer.long
        if (installationLength !in 1..MAX_ENVELOPE_FIELD_BYTES || environmentLength !in 2..MAX_ENVELOPE_FIELD_BYTES ||
            eventIdLength !in 1..MAX_EVENT_ID_BYTES || length !in 0..maxEventBytes ||
            installationLength.toLong() + environmentLength + eventIdLength + length != buffer.remaining().toLong()) {
            throw IOException("invalid queue entry length")
        }
        val installationId = ByteArray(installationLength).also { buffer.get(it) }
        val environment = ByteArray(environmentLength).also { buffer.get(it) }
        val eventId = ByteArray(eventIdLength).also { buffer.get(it) }
        val data = ByteArray(length).also { buffer.get(it) }
        val crc = checksum(priority == 1, createdAtMillis, sequence, installationId, environment, eventId, data)
        if (crc != expectedCrc) throw IOException("queue checksum mismatch")
        return RemoteDiskQueue.Entry(path, priority == 1, createdAtMillis, sequence, String(eventId, StandardCharsets.UTF_8),
            RemoteBatchEnvelope(installationId, environment), data, fileBytes)
    }

    fun write(path: Path, incident: Boolean, createdAtMillis: Long, sequence: Long,
        installationId: ByteArray, environment: ByteArray, eventId: ByteArray, data: ByteArray) {
        val header = ByteBuffer.allocate(HEADER_BYTES)
        header.putInt(MAGIC).put(VERSION).put(if (incident) 1.toByte() else 0.toByte())
            .putLong(createdAtMillis).putLong(sequence).putInt(installationId.size).putInt(environment.size)
            .putInt(eventId.size).putInt(data.size)
            .putLong(checksum(incident, createdAtMillis, sequence, installationId, environment, eventId, data))
        header.flip()
        FileChannel.open(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
            while (header.hasRemaining()) channel.write(header)
            val body = ByteBuffer.allocate(installationId.size + environment.size + eventId.size + data.size)
            body.put(installationId).put(environment).put(eventId).put(data)
            body.flip()
            while (body.hasRemaining()) channel.write(body)
        }
        QueueFiles.restrictPermissions(path, false)
    }

    private fun checksum(incident: Boolean, createdAtMillis: Long, sequence: Long,
        installationId: ByteArray, environment: ByteArray, eventId: ByteArray, data: ByteArray): Long {
        val metadata = ByteBuffer.allocate(1 + 1 + 8 + 8 + 4 + 4 + 4 + 4)
        metadata.put(VERSION).put(if (incident) 1.toByte() else 0.toByte()).putLong(createdAtMillis).putLong(sequence)
            .putInt(installationId.size).putInt(environment.size).putInt(eventId.size).putInt(data.size)
        return CRC32().apply {
            update(metadata.array()); update(installationId); update(environment); update(eventId); update(data)
        }.value
    }
}

/** 所有平台的目录身份与权限检查集中在一个安全边界。 */
private object QueueFiles {
    private val ownerOnly = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
    private val mutationPermissions = EnumSet.of(AclEntryPermission.WRITE_DATA, AclEntryPermission.APPEND_DATA,
        AclEntryPermission.WRITE_NAMED_ATTRS, AclEntryPermission.DELETE_CHILD, AclEntryPermission.WRITE_ATTRIBUTES,
        AclEntryPermission.DELETE, AclEntryPermission.WRITE_ACL, AclEntryPermission.WRITE_OWNER)

    fun prepareDirectory(path: Path) {
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(path, NOFOLLOW_LINKS)) throw IOException("Remote queue path is not an owned directory")
        } else {
            path.parent?.let { if (!Files.exists(it, NOFOLLOW_LINKS)) Files.createDirectories(it) }
            Files.createDirectory(path)
        }
        verifyOwner(path)
        restrictPermissions(path, true)
    }

    fun verifyOwner(path: Path) {
        val userName = System.getProperty("user.name")?.takeIf { it.isNotEmpty() } ?: return
        val expected = try { FileSystems.getDefault().userPrincipalLookupService.lookupPrincipalByName(userName) }
            catch (failure: Exception) {
                if (failure !is IOException && failure !is RuntimeException) throw failure
                return
            }
        if (!samePrincipal(Files.getOwner(path, NOFOLLOW_LINKS), expected)) {
            throw IOException("Remote queue directory must be owned by the current user")
        }
    }

    fun verifyAncestors(path: Path) {
        var current = path.parent
        while (current != null) {
            val attributes = Files.readAttributes(current, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attributes.isDirectory || attributes.isSymbolicLink) throw IOException("Remote queue ancestor is not a stable directory")
            val posix = Files.getFileAttributeView(current, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
            if (posix != null) {
                val permissions = posix.readAttributes().permissions()
                if (PosixFilePermission.GROUP_WRITE in permissions || PosixFilePermission.OTHERS_WRITE in permissions) {
                    throw IOException("Remote queue ancestor permits untrusted replacement")
                }
            } else {
                val acl = Files.getFileAttributeView(current, AclFileAttributeView::class.java, NOFOLLOW_LINKS)
                    ?: throw IOException("Remote queue filesystem cannot protect ancestor directories")
                for (entry in acl.acl) {
                    if (entry.type() == AclEntryType.ALLOW && !samePrincipal(acl.owner, entry.principal()) &&
                        entry.permissions().any { it in mutationPermissions }) {
                        throw IOException("Remote queue ancestor permits untrusted replacement")
                    }
                }
            }
            current = current.parent
        }
    }

    fun restrictPermissions(path: Path, directory: Boolean) {
        try {
            val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
            if (posix != null) {
                val permissions = if (directory) ownerOnly else EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                posix.setPermissions(permissions)
                if (posix.readAttributes().permissions() != permissions) throw IOException("Remote queue path is not owner-only")
                return
            }
            val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java, NOFOLLOW_LINKS)
                ?: throw IOException("Remote queue filesystem lacks owner-only ACLs")
            val expected = listOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission::class.java)).build())
            acl.acl = expected
            if (acl.acl != expected) throw IOException("Remote queue path is not owner-only")
        } catch (unsupported: UnsupportedOperationException) {
            throw IOException("Remote queue filesystem lacks owner-only permissions", unsupported)
        }
    }
    private fun samePrincipal(left: UserPrincipal, right: UserPrincipal): Boolean = left == right || left.name == right.name
}
