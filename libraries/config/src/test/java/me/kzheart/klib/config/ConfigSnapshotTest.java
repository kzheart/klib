package me.kzheart.klib.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigSnapshotTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void onlyValidatedAggregateIsPublishedAndOldReadersKeepConsistentView() {
        ConfigSnapshot<Catalog> snapshot = new ConfigSnapshot<Catalog>(catalog("old", 2));
        Catalog old = snapshot.value();
        ConfigSnapshot.Candidate<Catalog> candidate = snapshot.prepare(() -> catalog("new", 5));
        assertSame(old, snapshot.value());
        assertEquals(0, candidate.baseRevision());
        snapshot.commit(candidate);
        assertEquals(1, snapshot.revision());
        assertEquals(5, snapshot.value().prices.get(snapshot.value().featured));
        assertEquals(2, old.prices.get(old.featured));
        assertThrows(ConfigException.class, () -> snapshot.commit(candidate));
    }

    @Test
    void parsingOrCrossFileValidationFailureNeverChangesPublishedValue() {
        ConfigSnapshot<Catalog> snapshot = new ConfigSnapshot<Catalog>(catalog("old", 2));
        Catalog old = snapshot.value();
        ConfigMappingException parseFailure = YamlDocument.parse("shop.yml", "bad: true\n")
                .root().child("bad").mappingError("invalid price");
        assertSame(parseFailure, assertThrows(ConfigMappingException.class,
                () -> snapshot.prepare(() -> { throw parseFailure; })));
        assertThrows(ConfigException.class, () -> snapshot.prepare(
                () -> new Catalog(Collections.singletonMap("new", 3), "missing")));
        assertSame(old, snapshot.value());
        assertEquals(0, snapshot.revision());
    }

    @Test
    void lateCandidateCannotOverwriteNewerCommitAndForeignCandidateIsRejected() {
        ConfigSnapshot<String> snapshot = new ConfigSnapshot<String>("initial");
        ConfigSnapshot.Candidate<String> slow = snapshot.prepare(() -> "slow");
        snapshot.commit(snapshot.prepare(() -> "fast"));
        assertThrows(ConfigException.class, () -> snapshot.commit(slow));
        assertEquals("fast", snapshot.value());
        ConfigSnapshot<String> other = new ConfigSnapshot<String>("other");
        assertThrows(IllegalArgumentException.class, () -> other.commit(slow));
        assertThrows(NullPointerException.class, () -> snapshot.prepare(() -> null));
        assertThrows(NullPointerException.class, () -> snapshot.commit(null));
    }

    @Test
    void explicitPartialPolicyStillValidatesSurvivingReferences() {
        ConfigSnapshot<Catalog> snapshot = new ConfigSnapshot<Catalog>(catalog("old", 2));
        Map<String, ConfigException> diagnostics = new LinkedHashMap<String, ConfigException>();
        ConfigSnapshot.Candidate<Catalog> candidate = snapshot.prepare(() -> {
            Map<String, Integer> valid = new LinkedHashMap<String, Integer>();
            for (String name : Arrays.asList("valid.yml", "bad.yml")) {
                try {
                    if (name.equals("bad.yml")) throw new ConfigException("bad.yml: invalid price");
                    valid.put("new", 4);
                } catch (ConfigException failure) {
                    diagnostics.put(name, failure);
                }
            }
            return new Catalog(valid, "new");
        });
        assertEquals(1, diagnostics.size());
        snapshot.commit(candidate);
        assertEquals(4, snapshot.value().prices.get("new"));
    }

    @Test
    void prepareCapturesItsBaselineBeforeRunningTheBuilder() throws Exception {
        ConfigSnapshot<String> snapshot = new ConfigSnapshot<String>("initial");
        CountDownLatch builderStarted = new CountDownLatch(1);
        CountDownLatch finishBuilder = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ConfigSnapshot.Candidate<String>> preparing = executor.submit(() -> snapshot.prepare(() -> {
                builderStarted.countDown();
                await(finishBuilder);
                return "slow";
            }));
            await(builderStarted);
            snapshot.commit(snapshot.prepare(() -> "fast"));
            finishBuilder.countDown();
            ConfigSnapshot.Candidate<String> stale = preparing.get(5, TimeUnit.SECONDS);

            assertEquals(0, stale.baseRevision());
            assertThrows(ConfigException.class, () -> snapshot.commit(stale));
            assertEquals("fast", snapshot.value());
            assertEquals(1, snapshot.revision());
        } finally {
            finishBuilder.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void simultaneousCommitsOfOneCandidateHaveExactlyOneWinner() throws Exception {
        ConfigSnapshot<String> snapshot = new ConfigSnapshot<String>("initial");
        ConfigSnapshot.Candidate<String> candidate = snapshot.prepare(() -> "next");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> tryCommit(snapshot, candidate, ready, commit));
            Future<Boolean> second = executor.submit(() -> tryCommit(snapshot, candidate, ready, commit));
            await(ready);
            commit.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS) ^ second.get(5, TimeUnit.SECONDS));
            assertEquals("next", snapshot.value());
            assertEquals(1, snapshot.revision());
        } finally {
            commit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void explicitPartialPolicyCannotPublishSurvivingBrokenReferences() {
        ConfigSnapshot<Catalog> snapshot = new ConfigSnapshot<Catalog>(catalog("old", 2));
        Catalog old = snapshot.value();
        Map<String, ConfigException> diagnostics = new LinkedHashMap<String, ConfigException>();
        assertThrows(ConfigException.class, () -> snapshot.prepare(() -> {
            Map<String, Integer> valid = new LinkedHashMap<String, Integer>();
            try {
                throw new ConfigException("featured.yml: invalid price");
            } catch (ConfigException failure) {
                diagnostics.put("featured.yml", failure);
            }
            return new Catalog(valid, "featured");
        }));
        assertEquals(1, diagnostics.size());
        assertSame(old, snapshot.value());
        assertEquals(0, snapshot.revision());
    }

    @Test
    void businessImmutableCopyIsDetachedFromBuilderInputs() {
        Map<String, Integer> inputs = new LinkedHashMap<String, Integer>();
        inputs.put("new", 4);
        ConfigSnapshot<Catalog> snapshot = new ConfigSnapshot<Catalog>(catalog("old", 2));
        ConfigSnapshot.Candidate<Catalog> candidate = snapshot.prepare(() -> new Catalog(inputs, "new"));
        inputs.put("new", 99);
        snapshot.commit(candidate);
        assertEquals(4, snapshot.value().prices.get("new"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.value().prices.put("new", 88));
    }

    @Test
    void preparingAndPublishingMultipleSourcesDoesNotCommitTheirFiles() {
        Path pricesFile = temporaryDirectory.resolve("prices.yml");
        Path selectionFile = temporaryDirectory.resolve("selection.yml");
        FileConfigSource pricesSource = new FileConfigSource(pricesFile,
                "prices: {new: 4}\n", new MigrationRunner(), false);
        FileConfigSource selectionSource = new FileConfigSource(selectionFile,
                "featured: new\n", new MigrationRunner(), false);
        YamlConfigMapper mapper = new YamlConfigMapper(UnknownKeyPolicy.REJECT);
        ConfigSnapshot<Catalog> snapshot = new ConfigSnapshot<Catalog>(catalog("old", 2));
        ConfigSnapshot.Candidate<Catalog> candidate = snapshot.prepare(() -> {
            Prices prices = mapper.read(pricesSource.prepare().document().root(), Prices.class);
            Selection selection = mapper.read(selectionSource.prepare().document().root(), Selection.class);
            return new Catalog(prices.prices, selection.featured);
        });
        assertFalse(Files.exists(pricesFile));
        assertFalse(Files.exists(selectionFile));
        snapshot.commit(candidate);
        assertEquals(4, snapshot.value().prices.get("new"));
        assertFalse(Files.exists(pricesFile));
        assertFalse(Files.exists(selectionFile));
    }

    private static boolean tryCommit(ConfigSnapshot<String> snapshot,
            ConfigSnapshot.Candidate<String> candidate, CountDownLatch ready, CountDownLatch commit) {
        ready.countDown();
        await(commit);
        try {
            snapshot.commit(candidate);
            return true;
        } catch (ConfigException stale) {
            return false;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for concurrent test operation");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Concurrent test operation was interrupted", interrupted);
        }
    }

    private static Catalog catalog(String name, int price) {
        return new Catalog(Collections.singletonMap(name, price), name);
    }

    private static final class Prices {
        private Map<String, Integer> prices;
    }

    private static final class Selection {
        private String featured;
    }

    private static final class Catalog {
        private final Map<String, Integer> prices;
        private final String featured;

        private Catalog(Map<String, Integer> prices, String featured) {
            if (!prices.containsKey(featured)) throw new ConfigException("Featured item is missing");
            this.prices = Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(prices));
            this.featured = featured;
        }
    }
}
