package me.kzheart.klib.config;

import me.kzheart.klib.config.api.ConfigDocument;
import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigModuleTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void installsClasspathBackedCapabilityAndExtractsDefaults() throws Exception {
        Path resources = temporaryDirectory.resolve("resources");
        Files.createDirectories(resources.resolve("defaults"));
        Files.write(resources.resolve("defaults/settings.yml"), "name: klib\n".getBytes(StandardCharsets.UTF_8));
        ScopeImpl scope = new ScopeImpl("config-module");

        try (URLClassLoader loader = new URLClassLoader(new URL[]{resources.toUri().toURL()}, null)) {
            ConfigModule.install(scope, temporaryDirectory.resolve("data"), loader, "defaults");
            ConfigDocument<Settings> document = scope.config(Settings.class, "settings.yml");

            assertEquals("klib", document.value().name);
            assertEquals("name: klib\n", new String(Files.readAllBytes(
                    temporaryDirectory.resolve("data/settings.yml")), StandardCharsets.UTF_8));
        } finally {
            scope.close();
        }
    }

    @Test
    void rejectsPathsOutsideDataDirectory() throws Exception {
        ScopeImpl scope = new ScopeImpl("config-module-path");
        try (URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
            ConfigModule.install(scope, temporaryDirectory.resolve("data"), loader, "defaults");
            assertThrows(ConfigException.class, () -> scope.config(Settings.class, "../secret.yml"));
        } finally {
            scope.close();
        }
    }

    public static final class Settings {
        public String name;
    }
}
