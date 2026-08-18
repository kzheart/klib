package me.kzheart.klib.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallationIdTest {
    @TempDir Path temporaryDirectory;

    @Test
    void randomIdPersistsForOneProduct() {
        String first = InstallationId.forProduct("dev.market", temporaryDirectory).get();
        assertEquals(first, InstallationId.forProduct("dev.market", temporaryDirectory).get());
        assertTrue(first.startsWith("inst_"));
    }

    @Test
    void differentProductsCannotShareInstallationId() {
        assertNotEquals(
                InstallationId.forProduct("dev.market", temporaryDirectory).get(),
                InstallationId.forProduct("dev.chat", temporaryDirectory).get());
    }

    @Test
    void pathLikeProductNamesCannotCollide() {
        assertNotEquals(
                InstallationId.forProduct("catalog/a", temporaryDirectory).get(),
                InstallationId.forProduct("catalog?a", temporaryDirectory).get());
    }

    @Test
    void deletingDataCreatesNewIdentity() throws Exception {
        String first = InstallationId.forProduct("dev.market", temporaryDirectory).get();
        Path file = temporaryDirectory.resolve(InstallationId.DIRECTORY_NAME)
                .resolve("installation-" + InstallationId.productFileKey("dev.market"));
        Files.delete(file);
        assertNotEquals(first, InstallationId.forProduct("dev.market", temporaryDirectory).get());
    }

    @Test
    void corruptCacheIsReplacedWithRandomId() throws Exception {
        Path directory = temporaryDirectory.resolve(InstallationId.DIRECTORY_NAME);
        Files.createDirectories(directory);
        Path file = directory.resolve(
                "installation-" + InstallationId.productFileKey("dev.market"));
        Files.write(file, "hardware-derived-id".getBytes(StandardCharsets.UTF_8));
        String value = InstallationId.forProduct("dev.market", temporaryDirectory).get();
        assertTrue(value.startsWith("inst_"));
        assertEquals(value, new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }
}
