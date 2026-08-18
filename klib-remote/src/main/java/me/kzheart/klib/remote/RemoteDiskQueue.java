package me.kzheart.klib.remote;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/** 只由 RemoteDelivery 工作线程访问的磁盘队列。 */
final class RemoteDiskQueue implements AutoCloseable {
    private static final int MAGIC = 0x4b525131;
    private static final byte VERSION = 1;
    private static final int HEADER_BYTES = 4 + 1 + 1 + 8 + 8 + 4 + 4 + 4 + 4 + 8;
    private static final int MAX_ENVELOPE_FIELD_BYTES = 8 * 1024;
    private static final int MAX_EVENT_ID_BYTES = 1024;
    private static final String ENTRY_SUFFIX = ".rqe";
    private static final String TEMP_SUFFIX = ".tmp";
    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<AclEntryPermission> ACL_MUTATION_PERMISSIONS = EnumSet.of(
            AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA,
            AclEntryPermission.WRITE_NAMED_ATTRS,
            AclEntryPermission.DELETE_CHILD,
            AclEntryPermission.WRITE_ATTRIBUTES,
            AclEntryPermission.DELETE,
            AclEntryPermission.WRITE_ACL,
            AclEntryPermission.WRITE_OWNER);

    private final Path directory;
    private final Path quarantine;
    private final long maxBytes;
    private final int maxEntries;
    private final long maxQuarantineBytes;
    private final int maxQuarantineEntries;
    private final int maxEventBytes;
    private final long ttlMillis;
    private final FileChannel lockChannel;
    private final FileLock directoryLock;
    private final Object directoryKey;
    private final Deque<Entry> incidents = new ArrayDeque<Entry>();
    private final Deque<Entry> logs = new ArrayDeque<Entry>();
    private long bytes;
    private long nextSequence;
    private volatile int entryCount;
    private long quarantineBytes;
    private int quarantineEntries;

    RemoteDiskQueue(Path directory, long maxBytes, int maxEntries,
            int maxEventBytes, long ttlMillis, byte[] queueIdentity)
            throws IOException {
        Path requestedDirectory = directory.toAbsolutePath().normalize();
        prepareOwnedDirectory(requestedDirectory);
        this.directory = requestedDirectory.toRealPath();
        this.quarantine = this.directory.resolve("quarantine");
        this.maxBytes = maxBytes;
        this.maxEntries = maxEntries;
        this.maxQuarantineBytes = Math.max(1L,
                Math.min(1024L * 1024L, maxBytes / 16L));
        this.maxQuarantineEntries = Math.max(1, Math.min(64, maxEntries / 16));
        this.maxEventBytes = maxEventBytes;
        this.ttlMillis = ttlMillis;
        verifyOwner(this.directory);
        restrictPermissions(this.directory, true);
        verifyProtectedAncestorChain(this.directory);
        prepareOwnedDirectory(this.quarantine);
        BasicFileAttributes directoryAttributes = Files.readAttributes(this.directory,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        directoryKey = directoryAttributes.fileKey();
        if (directoryKey == null) {
            throw new IOException("Remote queue filesystem cannot bind directory identity");
        }
        Path lockPath = this.directory.resolve(".queue.lock");
        lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        restrictPermissions(lockPath, false);
        FileLock acquired;
        try {
            acquired = lockChannel.tryLock();
        } catch (IOException | RuntimeException failure) {
            lockChannel.close();
            throw failure;
        }
        if (acquired == null) {
            lockChannel.close();
            throw new IOException("Remote queue directory is already in use");
        }
        directoryLock = acquired;
        try {
            if (!directoryBindingIntact()) {
                throw new IOException("Remote queue directory changed during initialization");
            }
            bindIdentity(queueIdentity);
            recoverQuarantine();
            recover(System.currentTimeMillis());
        } catch (IOException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    private void recoverQuarantine() throws IOException {
        List<Quarantined> files = quarantineFiles();
        quarantineEntries = files.size();
        quarantineBytes = 0L;
        for (Quarantined file : files) quarantineBytes += file.bytes;
        pruneQuarantine(0L, 0);
    }

    private void bindIdentity(byte[] identity) throws IOException {
        if (identity == null || identity.length != 32) {
            throw new IOException("Remote queue identity is invalid");
        }
        byte[] expected = hex(identity).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Path marker = directory.resolve("queue.identity");
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(marker) != expected.length) {
                throw new IOException("Remote queue identity file is invalid");
            }
            ByteBuffer actual = ByteBuffer.allocate(expected.length);
            try (FileChannel channel = FileChannel.open(marker,
                    StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                while (actual.hasRemaining()) {
                    if (channel.read(actual) < 0) throw new EOFException("truncated identity");
                }
            }
            if (!java.security.MessageDigest.isEqual(expected, actual.array())) {
                throw new IOException("Remote queue belongs to a different endpoint or key");
            }
            return;
        }
        Path temporary = directory.resolve("queue.identity." + UUID.randomUUID() + TEMP_SUFFIX);
        try {
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer data = ByteBuffer.wrap(expected);
                while (data.hasRemaining()) channel.write(data);
            }
            restrictPermissions(temporary, false);
            Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String hex(byte[] value) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] output = new char[value.length * 2];
        for (int index = 0; index < value.length; index++) {
            int current = value[index] & 0xff;
            output[index * 2] = alphabet[current >>> 4];
            output[index * 2 + 1] = alphabet[current & 0x0f];
        }
        return new String(output);
    }

    StoreResult store(boolean incident, long createdAtMillis,
            String eventId, RemoteBatchEnvelope envelope, byte[] data) {
        if (!directoryBindingIntact()) return new StoreResult(false, 0);
        byte[] installationId = envelope.installationId();
        byte[] environment = envelope.environment();
        byte[] encodedEventId = eventId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long fileBytes = (long) HEADER_BYTES + installationId.length
                + environment.length + encodedEventId.length + data.length;
        if (data.length > maxEventBytes
                || installationId.length > MAX_ENVELOPE_FIELD_BYTES
                || environment.length > MAX_ENVELOPE_FIELD_BYTES
                || encodedEventId.length < 1 || encodedEventId.length > MAX_EVENT_ID_BYTES
                || fileBytes > maxBytes) {
            return new StoreResult(false, 0);
        }
        int evicted = makeRoom(incident, fileBytes, 1);
        if (bytes + quarantineBytes + fileBytes > maxBytes || size() >= maxEntries) {
            return new StoreResult(false, evicted);
        }
        long sequence = ++nextSequence;
        String stem = String.format(java.util.Locale.ROOT, "%019d-%019d-%s",
                createdAtMillis, sequence, UUID.randomUUID().toString());
        Path temporary = directory.resolve(stem + TEMP_SUFFIX);
        Path target = directory.resolve(stem + ENTRY_SUFFIX);
        try {
            if (!directoryBindingIntact()) return new StoreResult(false, evicted);
            write(temporary, incident, createdAtMillis, sequence,
                    installationId, environment, encodedEventId, data);
            if (!directoryBindingIntact()) return new StoreResult(false, evicted);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            Entry entry = new Entry(target, incident, createdAtMillis, sequence,
                    eventId, envelope, Arrays.copyOf(data, data.length), fileBytes);
            entries(incident).addLast(entry);
            bytes += entry.fileBytes;
            entryCount++;
            return new StoreResult(true, evicted);
        } catch (IOException failure) {
            try {
                if (directoryBindingIntact()) Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // 下一次恢复会隔离未完成的临时文件。
            }
            return new StoreResult(false, evicted);
        }
    }

    List<Entry> batch(int maxEvents, int maxBytes) {
        List<Entry> result = new ArrayList<Entry>();
        if (!directoryBindingIntact()) return result;
        int used = 0;
        Entry first = incidents.peekFirst();
        if (first == null) first = logs.peekFirst();
        if (first == null) return result;
        used = append(result, incidents, first.envelope, maxEvents, maxBytes, used);
        append(result, logs, first.envelope, maxEvents, maxBytes, used);
        return result;
    }

    boolean remove(Entry entry) {
        if (!directoryBindingIntact()) return false;
        Deque<Entry> source = entries(entry.incident);
        if (!source.remove(entry)) return true;
        bytes -= entry.fileBytes;
        entryCount--;
        try {
            Files.deleteIfExists(entry.path);
            return true;
        } catch (IOException ignored) {
            source.addFirst(entry);
            bytes += entry.fileBytes;
            entryCount++;
            return false;
        }
    }

    int purgeExpired(long nowMillis) {
        int purged = purgeExpired(incidents, nowMillis);
        return purged + purgeExpired(logs, nowMillis);
    }

    int discardOversized(int maxEventBytes) {
        int discarded = discardOversized(incidents, maxEventBytes);
        return discarded + discardOversized(logs, maxEventBytes);
    }

    int size() {
        return entryCount;
    }

    @Override public void close() {
        try {
            directoryLock.release();
        } catch (IOException ignored) {
            // 关闭路径不向插件生命周期抛出。
        }
        try {
            lockChannel.close();
        } catch (IOException ignored) {
            // 关闭路径不向插件生命周期抛出。
        }
    }

    private void recover(long nowMillis) throws IOException {
        List<Entry> recovered = new ArrayList<Entry>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path path : files) {
                String name = path.getFileName().toString();
                if ("quarantine".equals(name)) continue;
                if (!name.endsWith(ENTRY_SUFFIX)) {
                    if (name.endsWith(TEMP_SUFFIX)) quarantine(path);
                    continue;
                }
                Entry entry = read(path);
                if (entry == null) continue;
                if (expired(entry, nowMillis)) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException failure) {
                        quarantine(path);
                    }
                } else {
                    recovered.add(entry);
                }
            }
        }
        Collections.sort(recovered, Comparator.comparingLong((Entry value) -> value.sequence)
                .thenComparing(value -> value.path.getFileName().toString()));
        for (Entry entry : recovered) {
            if (entry.fileBytes > maxBytes) {
                quarantine(entry.path);
                continue;
            }
            makeRoom(entry.incident, entry.fileBytes, 1);
            if (bytes + quarantineBytes + entry.fileBytes > maxBytes
                    || size() >= maxEntries) {
                quarantine(entry.path);
                continue;
            }
            entries(entry.incident).addLast(entry);
            bytes += entry.fileBytes;
            entryCount++;
            nextSequence = Math.max(nextSequence, entry.sequence);
        }
    }

    private Entry read(Path path) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                quarantine(path);
                return null;
            }
            long fileBytes = Files.size(path);
            if (fileBytes < HEADER_BYTES || fileBytes > (long) HEADER_BYTES + maxEventBytes
                    + MAX_ENVELOPE_FIELD_BYTES * 2L + MAX_EVENT_ID_BYTES) {
                quarantine(path);
                return null;
            }
            ByteBuffer buffer = ByteBuffer.allocate((int) fileBytes);
            try (FileChannel channel = FileChannel.open(path,
                    StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) throw new EOFException("truncated queue entry");
                }
            }
            buffer.flip();
            if (buffer.getInt() != MAGIC || buffer.get() != VERSION) {
                throw new IOException("invalid queue entry header");
            }
            byte priority = buffer.get();
            if (priority != 0 && priority != 1) throw new IOException("invalid priority");
            long createdAtMillis = buffer.getLong();
            if (createdAtMillis < 0L
                    || createdAtMillis > System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5L)) {
                throw new IOException("invalid queue entry timestamp");
            }
            long sequence = buffer.getLong();
            if (sequence < 1L) throw new IOException("invalid queue entry sequence");
            int installationLength = buffer.getInt();
            int environmentLength = buffer.getInt();
            int eventIdLength = buffer.getInt();
            int length = buffer.getInt();
            long expectedCrc = buffer.getLong();
            if (installationLength < 1 || installationLength > MAX_ENVELOPE_FIELD_BYTES
                    || environmentLength < 2 || environmentLength > MAX_ENVELOPE_FIELD_BYTES
                    || eventIdLength < 1 || eventIdLength > MAX_EVENT_ID_BYTES
                    || length < 0 || length > maxEventBytes
                    || (long) installationLength + environmentLength
                    + eventIdLength + length != buffer.remaining()) {
                throw new IOException("invalid queue entry length");
            }
            byte[] installationId = new byte[installationLength];
            byte[] environment = new byte[environmentLength];
            byte[] eventId = new byte[eventIdLength];
            byte[] data = new byte[length];
            buffer.get(installationId);
            buffer.get(environment);
            buffer.get(eventId);
            buffer.get(data);
            CRC32 crc = new CRC32();
            updateMetadataCrc(crc, priority == 1, createdAtMillis, sequence,
                    installationLength, environmentLength, eventIdLength, length);
            crc.update(installationId);
            crc.update(environment);
            crc.update(eventId);
            crc.update(data);
            if (crc.getValue() != expectedCrc) throw new IOException("queue checksum mismatch");
            return new Entry(path, priority == 1, createdAtMillis, sequence,
                    new String(eventId, java.nio.charset.StandardCharsets.UTF_8),
                    new RemoteBatchEnvelope(installationId, environment), data, fileBytes);
        } catch (IOException | RuntimeException failure) {
            quarantine(path);
            return null;
        }
    }

    private void write(Path path, boolean incident, long createdAtMillis, long sequence,
            byte[] installationId, byte[] environment, byte[] eventId, byte[] data)
            throws IOException {
        CRC32 crc = new CRC32();
        updateMetadataCrc(crc, incident, createdAtMillis, sequence,
                installationId.length, environment.length, eventId.length, data.length);
        crc.update(installationId);
        crc.update(environment);
        crc.update(eventId);
        crc.update(data);
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        header.putInt(MAGIC).put(VERSION).put(incident ? (byte) 1 : (byte) 0)
                .putLong(createdAtMillis).putLong(sequence).putInt(installationId.length)
                .putInt(environment.length).putInt(eventId.length).putInt(data.length)
                .putLong(crc.getValue()).flip();
        try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            while (header.hasRemaining()) channel.write(header);
            ByteBuffer body = ByteBuffer.allocate(installationId.length
                    + environment.length + eventId.length + data.length);
            body.put(installationId).put(environment).put(eventId).put(data).flip();
            while (body.hasRemaining()) channel.write(body);
        }
        restrictPermissions(path, false);
    }

    private int makeRoom(boolean incident, long requiredBytes, int requiredEntries) {
        int evicted = 0;
        while (bytes + quarantineBytes + requiredBytes > maxBytes
                || size() + requiredEntries > maxEntries) {
            Entry victim;
            if (incident) {
                victim = logs.peekFirst();
                if (victim == null) victim = incidents.peekFirst();
            } else {
                victim = logs.peekFirst();
            }
            if (victim == null) break;
            if (!remove(victim)) break;
            evicted++;
        }
        return evicted;
    }

    private int append(List<Entry> result, Deque<Entry> source,
            RemoteBatchEnvelope envelope, int maxEvents, int maxBatchBytes, int used) {
        for (Entry entry : source) {
            if (result.size() >= maxEvents) break;
            if (!entry.envelope.sameAs(envelope)) continue;
            if (entry.data.length > maxBatchBytes) break;
            if (used + entry.data.length > maxBatchBytes) break;
            result.add(entry);
            used += entry.data.length;
        }
        return used;
    }

    private int purgeExpired(Deque<Entry> entries, long nowMillis) {
        int purged = 0;
        for (Entry entry : new ArrayList<Entry>(entries)) {
            if (expired(entry, nowMillis)) {
                if (!remove(entry)) break;
                purged++;
            }
        }
        return purged;
    }

    private int discardOversized(Deque<Entry> entries, int maxEventBytes) {
        List<Entry> oversized = new ArrayList<Entry>();
        for (Entry entry : entries) {
            if (entry.data.length > maxEventBytes) oversized.add(entry);
        }
        int discarded = 0;
        for (Entry entry : oversized) {
            if (!remove(entry)) break;
            discarded++;
        }
        return discarded;
    }

    private boolean expired(Entry entry, long nowMillis) {
        return nowMillis - entry.createdAtMillis >= ttlMillis;
    }

    private Deque<Entry> entries(boolean incident) {
        return incident ? incidents : logs;
    }

    private void quarantine(Path path) {
        try {
            if (!path.normalize().getParent().equals(directory)) return;
            long fileBytes = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    ? Files.size(path) : 0L;
            if (fileBytes > maxQuarantineBytes) {
                Files.deleteIfExists(path);
                return;
            }
            pruneQuarantine(fileBytes, 1);
            if (quarantineBytes + fileBytes > maxQuarantineBytes
                    || quarantineEntries >= maxQuarantineEntries) {
                Files.deleteIfExists(path);
                return;
            }
            Path target = quarantine.resolve(path.getFileName().toString() + ".corrupt-"
                    + UUID.randomUUID().toString());
            Files.move(path, target, StandardCopyOption.ATOMIC_MOVE);
            quarantineBytes += fileBytes;
            quarantineEntries++;
        } catch (AtomicMoveNotSupportedException failure) {
            try {
                Path target = quarantine.resolve(path.getFileName().toString()
                        + ".corrupt-" + UUID.randomUUID().toString());
                Files.move(path, target);
                quarantineBytes += Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                        ? Files.size(target) : 0L;
                quarantineEntries++;
            } catch (IOException ignored) {
                // 无法安全隔离时保持原文件，后续恢复仍会拒绝读取。
            }
        } catch (IOException | RuntimeException ignored) {
            // 恢复路径不会因单个恶意或损坏文件而停止。
        }
    }

    private void pruneQuarantine(long requiredBytes, int requiredEntries) {
        try {
            List<Quarantined> files = quarantineFiles();
            for (Quarantined file : files) {
                if (quarantineBytes + requiredBytes <= maxQuarantineBytes
                        && quarantineEntries + requiredEntries <= maxQuarantineEntries) break;
                try {
                    if (Files.deleteIfExists(file.path)) {
                        quarantineBytes -= file.bytes;
                        quarantineEntries--;
                    }
                } catch (IOException ignored) {
                    break;
                }
            }
        } catch (IOException ignored) {
            // 隔离区不可枚举时拒绝增加新隔离文件。
        }
    }

    private List<Quarantined> quarantineFiles() throws IOException {
        List<Quarantined> files = new ArrayList<Quarantined>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(quarantine)) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(path);
                    continue;
                }
                files.add(new Quarantined(path, Files.size(path),
                        Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()));
            }
        }
        Collections.sort(files, Comparator.comparingLong((Quarantined value) -> value.modifiedAt)
                .thenComparing(value -> value.path.getFileName().toString()));
        return files;
    }

    private static void updateMetadataCrc(CRC32 crc, boolean incident,
            long createdAtMillis, long sequence, int installationLength,
            int environmentLength, int eventIdLength, int dataLength) {
        ByteBuffer metadata = ByteBuffer.allocate(1 + 1 + 8 + 8 + 4 + 4 + 4 + 4);
        metadata.put(VERSION).put(incident ? (byte) 1 : (byte) 0)
                .putLong(createdAtMillis).putLong(sequence).putInt(installationLength)
                .putInt(environmentLength).putInt(eventIdLength).putInt(dataLength);
        crc.update(metadata.array());
    }

    private static void prepareOwnedDirectory(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Remote queue path is not an owned directory");
            }
        } else {
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(parent);
            }
            Files.createDirectory(path);
        }
        verifyOwner(path);
        restrictPermissions(path, true);
    }

    private boolean directoryBindingIntact() {
        try {
            verifyProtectedAncestorChain(directory);
            BasicFileAttributes current = Files.readAttributes(directory,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return current.isDirectory() && directoryKey.equals(current.fileKey());
        } catch (IOException | RuntimeException failure) {
            return false;
        }
    }

    private static void verifyOwner(Path path) throws IOException {
        String userName = System.getProperty("user.name");
        if (userName == null || userName.isEmpty()) return;
        UserPrincipal expected;
        try {
            expected = FileSystems.getDefault().getUserPrincipalLookupService()
                    .lookupPrincipalByName(userName);
        } catch (IOException | RuntimeException unsupported) {
            return;
        }
        UserPrincipal actual = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        if (!actual.equals(expected) && !actual.getName().equals(expected.getName())) {
            throw new IOException("Remote queue directory must be owned by the current user");
        }
    }

    private static void verifyProtectedAncestorChain(Path path) throws IOException {
        Path current = path.getParent();
        while (current != null) {
            BasicFileAttributes attributes = Files.readAttributes(current,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
                throw new IOException("Remote queue ancestor is not a stable directory");
            }
            PosixFileAttributeView posix = Files.getFileAttributeView(
                    current, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (posix != null) {
                Set<PosixFilePermission> permissions = posix.readAttributes().permissions();
                if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                        || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                    throw new IOException("Remote queue ancestor permits untrusted replacement");
                }
            } else {
                AclFileAttributeView acl = Files.getFileAttributeView(
                        current, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (acl == null) {
                    throw new IOException("Remote queue filesystem cannot protect ancestor directories");
                }
                UserPrincipal owner = acl.getOwner();
                for (AclEntry entry : acl.getAcl()) {
                    if (entry.type() != AclEntryType.ALLOW
                            || samePrincipal(owner, entry.principal())) continue;
                    if (!Collections.disjoint(entry.permissions(), ACL_MUTATION_PERMISSIONS)) {
                        throw new IOException("Remote queue ancestor permits untrusted replacement");
                    }
                }
            }
            current = current.getParent();
        }
    }

    private static boolean samePrincipal(UserPrincipal left, UserPrincipal right) {
        return left.equals(right) || left.getName().equals(right.getName());
    }

    static void restrictPermissions(Path path, boolean directory) throws IOException {
        try {
            PosixFileAttributeView posix = Files.getFileAttributeView(
                    path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (posix != null) {
                Set<PosixFilePermission> permissions = directory
                        ? OWNER_ONLY
                        : EnumSet.of(PosixFilePermission.OWNER_READ,
                                PosixFilePermission.OWNER_WRITE);
                posix.setPermissions(permissions);
                if (!posix.readAttributes().permissions().equals(permissions)) {
                    throw new IOException("Remote queue path is not owner-only");
                }
                return;
            }
            AclFileAttributeView acl = Files.getFileAttributeView(
                    path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null) throw new IOException("Remote queue filesystem lacks owner-only ACLs");
            restrictAcl(acl);
        } catch (UnsupportedOperationException unsupported) {
            throw new IOException("Remote queue filesystem lacks owner-only permissions",
                    unsupported);
        }
    }

    private static void restrictAcl(AclFileAttributeView acl) throws IOException {
        AclEntry ownerOnly = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
        List<AclEntry> expected = Collections.singletonList(ownerOnly);
        acl.setAcl(expected);
        if (!acl.getAcl().equals(expected)) {
            throw new IOException("Remote queue path is not owner-only");
        }
    }

    static final class Entry {
        private final Path path;
        private final boolean incident;
        private final long createdAtMillis;
        private final long sequence;
        private final String eventId;
        private final RemoteBatchEnvelope envelope;
        private final byte[] data;
        private final long fileBytes;

        private Entry(Path path, boolean incident, long createdAtMillis, long sequence,
                String eventId, RemoteBatchEnvelope envelope, byte[] data, long fileBytes) {
            this.path = path;
            this.incident = incident;
            this.createdAtMillis = createdAtMillis;
            this.sequence = sequence;
            this.eventId = eventId;
            this.envelope = envelope;
            this.data = data;
            this.fileBytes = fileBytes;
        }

        byte[] data() { return data; }
        String eventId() { return eventId; }
        RemoteBatchEnvelope envelope() { return envelope; }
    }

    static final class StoreResult {
        private final boolean stored;
        private final int evicted;

        private StoreResult(boolean stored, int evicted) {
            this.stored = stored;
            this.evicted = evicted;
        }

        boolean stored() { return stored; }
        int evicted() { return evicted; }
    }

    private static final class Quarantined {
        private final Path path;
        private final long bytes;
        private final long modifiedAt;

        private Quarantined(Path path, long bytes, long modifiedAt) {
            this.path = path;
            this.bytes = bytes;
            this.modifiedAt = modifiedAt;
        }
    }
}
