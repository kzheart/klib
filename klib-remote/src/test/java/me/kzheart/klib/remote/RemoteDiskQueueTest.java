package me.kzheart.klib.remote;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteDiskQueueTest {
    @TempDir Path temporaryDirectory;

    @Test
    void failedDeletionStopsCapacityEvictionInsteadOfLooping() throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews()
                .contains("posix"));
        RemoteDiskQueue queue = new RemoteDiskQueue(temporaryDirectory,
                220L, 10, 180, TimeUnit.HOURS.toMillis(1L), identity(1));
        RemoteBatchEnvelope envelope = envelope();
        byte[] log = event("log-that-fills-the-queue");
        assertTrue(queue.store(false, System.currentTimeMillis(), "log", envelope, log).stored());
        Files.setPosixFilePermissions(temporaryDirectory, EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        try {
            long started = System.nanoTime();
            assertFalse(queue.store(true, System.currentTimeMillis(), "incident", envelope,
                    event("incident-needing-room")).stored());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000L);
        } finally {
            Files.setPosixFilePermissions(temporaryDirectory, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
            queue.close();
        }
    }

    @Test
    void incidentEvictsOldestLogAndIsBatchedFirst() throws Exception {
        RemoteDiskQueue queue = new RemoteDiskQueue(temporaryDirectory,
                220L, 10, 180, TimeUnit.HOURS.toMillis(1L), identity(1));
        try {
            RemoteBatchEnvelope envelope = envelope();
            assertTrue(queue.store(false, 1L, "old-log", envelope, event("old-log")).stored());
            assertTrue(queue.store(false, 2L, "new-log", envelope, event("new-log")).stored());
            assertTrue(queue.store(true, 3L, "incident", envelope, event("incident")).stored());

            List<RemoteDiskQueue.Entry> batch = queue.batch(10, 1024);
            assertTrue(text(batch.get(0)).contains("incident"));
            assertTrue(text(batch.get(1)).contains("new-log"));
            assertFalse(text(batch.get(1)).contains("old-log"));
        } finally {
            queue.close();
        }
    }

    @Test
    void queueCannotBeReopenedForDifferentEndpointOrKeyIdentity() throws Exception {
        RemoteDiskQueue first = new RemoteDiskQueue(temporaryDirectory,
                1024L, 10, 180, TimeUnit.HOURS.toMillis(1L), identity(1));
        first.close();

        assertThrows(java.io.IOException.class, () -> new RemoteDiskQueue(temporaryDirectory,
                1024L, 10, 180, TimeUnit.HOURS.toMillis(1L), identity(2)));
    }

    @Test
    void replacingQueueDirectoryCannotRedirectLaterWrites() throws Exception {
        Path queuePath = temporaryDirectory.resolve("queue");
        Files.createDirectory(queuePath);
        RemoteDiskQueue queue = new RemoteDiskQueue(queuePath,
                2048L, 10, 180, TimeUnit.HOURS.toMillis(1L), identity(1));
        Path original = temporaryDirectory.resolve("original");
        Path attacker = temporaryDirectory.resolve("attacker");
        Files.createDirectory(attacker);
        try {
            try {
                Files.move(queuePath, original, StandardCopyOption.ATOMIC_MOVE);
                Files.createSymbolicLink(queuePath, attacker);
            } catch (UnsupportedOperationException | java.io.IOException unsupported) {
                Assumptions.abort("filesystem cannot replace a live directory with a symlink");
            }

            assertFalse(queue.store(false, System.currentTimeMillis(), "event", envelope(),
                    event("must-not-be-redirected")).stored());
            try (java.util.stream.Stream<Path> files = Files.list(attacker)) {
                assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".rqe")));
            }
        } finally {
            queue.close();
        }
    }

    @Test
    void writableAncestorIsRejectedBeforeSensitiveQueueWrites() throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews()
                .contains("posix"));
        Path parent = temporaryDirectory.resolve("writable-parent");
        Files.createDirectory(parent);
        Files.setPosixFilePermissions(parent, EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_WRITE));
        assertThrows(java.io.IOException.class, () -> new RemoteDiskQueue(parent.resolve("queue"),
                2048L, 10, 180, TimeUnit.HOURS.toMillis(1L), identity(1)));
    }

    @Test
    void makingAncestorWritableAfterOpenStopsLaterWrites() throws Exception {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews()
                .contains("posix"));
        Path parent = temporaryDirectory.resolve("protected-parent");
        Files.createDirectory(parent);
        Path queuePath = parent.resolve("queue");
        RemoteDiskQueue queue = new RemoteDiskQueue(queuePath,
                2048L, 10, 180, TimeUnit.HOURS.toMillis(1L), identity(1));
        try {
            Files.setPosixFilePermissions(parent, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OTHERS_WRITE));
            assertFalse(queue.store(false, System.currentTimeMillis(), "event", envelope(),
                    event("must-not-be-written")).stored());
            try (java.util.stream.Stream<Path> files = Files.list(queuePath)) {
                assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".rqe")));
            }
        } finally {
            Files.setPosixFilePermissions(parent, EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
            queue.close();
        }
    }

    @Test
    void ownerOnlyPermissionsFailClosedWithoutPosixOrAclSupport() throws Exception {
        Path archive = temporaryDirectory.resolve("queue.zip");
        java.util.Map<String, String> environment = new HashMap<String, String>();
        environment.put("create", "true");
        try (java.nio.file.FileSystem zip = FileSystems.newFileSystem(
                URI.create("jar:" + archive.toUri()), environment)) {
            Path directory = zip.getPath("/queue");
            Files.createDirectory(directory);
            assertThrows(java.io.IOException.class,
                    () -> RemoteDiskQueue.restrictPermissions(directory, true));
        }
    }

    @Test
    void recoveryPurgesExpiredEntriesAndKeepsEnvelopeSnapshot() throws Exception {
        RemoteBatchEnvelope original = new RemoteBatchEnvelope(
                "inst-old".getBytes(StandardCharsets.UTF_8),
                "{\"plugin_version\":\"old\"}".getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        RemoteDiskQueue first = new RemoteDiskQueue(temporaryDirectory,
                2048L, 10, 180, TimeUnit.HOURS.toMillis(1L), identity(1));
        assertTrue(first.store(false, now, "stable", original, event("stable")).stored());
        first.close();

        RemoteDiskQueue recovered = new RemoteDiskQueue(temporaryDirectory,
                2048L, 10, 180, TimeUnit.HOURS.toMillis(1L), identity(1));
        List<RemoteDiskQueue.Entry> batch = recovered.batch(10, 1024);
        assertTrue(batch.get(0).envelope().sameAs(original));
        recovered.close();

        RemoteDiskQueue expired = new RemoteDiskQueue(temporaryDirectory,
                2048L, 10, 180, 1L, identity(1));
        assertTrue(expired.size() == 0);
        expired.close();
    }

    private static RemoteBatchEnvelope envelope() {
        return new RemoteBatchEnvelope("inst-test".getBytes(StandardCharsets.UTF_8),
                "{}".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] identity(int value) {
        byte[] identity = new byte[32];
        identity[0] = (byte) value;
        return identity;
    }

    private static byte[] event(String message) {
        return ("{\"event_id\":\"" + message + "\",\"type\":\"log\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String text(RemoteDiskQueue.Entry entry) {
        return new String(entry.data(), StandardCharsets.UTF_8);
    }
}
