package me.kzheart.klib.config;

import me.kzheart.klib.config.api.ConfigDocument;
import me.kzheart.klib.scope.ScopeImpl;
import me.kzheart.klib.scope.capability.ConfigCapability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    @Test
    void explicitMapperControlsInstalledDocumentsAndRejectsUnknownKeys() throws Exception {
        Path resources = temporaryDirectory.resolve("resources");
        Files.createDirectories(resources.resolve("defaults"));
        Files.write(resources.resolve("defaults/settings.yml"), "name: Old\n".getBytes(StandardCharsets.UTF_8));
        Path data = temporaryDirectory.resolve("data");
        ScopeImpl scope = new ScopeImpl("config-module-strict");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{resources.toUri().toURL()}, null)) {
            ConfigModule.install(scope, data, loader, "defaults", new YamlConfigMapper(UnknownKeyPolicy.REJECT));
            ConfigDocument<Settings> document = scope.config(Settings.class, "settings.yml");
            Settings previous = document.value();
            String invalid = "name: New\nmisspelled: true\n";
            Files.write(data.resolve("settings.yml"), invalid.getBytes(StandardCharsets.UTF_8));

            assertThrows(ConfigMappingException.class, document::reload);
            assertSame(previous, document.value());
            assertEquals(invalid, new String(Files.readAllBytes(data.resolve("settings.yml")), StandardCharsets.UTF_8));
        } finally {
            scope.close();
        }
    }

    @Test
    void advancedMapperOverloadComposesCustomConversionWithMigration() throws Exception {
        Path resources = temporaryDirectory.resolve("resources");
        Files.createDirectories(resources.resolve("defaults"));
        Files.write(resources.resolve("defaults/settings.yml"), "name: default\n".getBytes(StandardCharsets.UTF_8));
        Path data = temporaryDirectory.resolve("data");
        Files.createDirectories(data);
        Files.write(data.resolve("settings.yml"), "legacy: live\n".getBytes(StandardCharsets.UTF_8));
        ScopeImpl scope = new ScopeImpl("config-module-mapper-migrations");
        YamlConfigMapper mapper = new YamlConfigMapper(UnknownKeyPolicy.REJECT)
                .registerConverter(String.class, node -> String.valueOf(node.raw()).toUpperCase(Locale.ROOT));
        try (URLClassLoader loader = new URLClassLoader(new URL[]{resources.toUri().toURL()}, null)) {
            ConfigModule.install(scope, data, loader, "defaults",
                    path -> new MigrationRunner().add(1, Migrations.rename("legacy", "name")), mapper);
            assertEquals("LIVE", scope.config(Settings.class, "settings.yml").value().name);
        } finally {
            scope.close();
        }
    }

    @Test
    void originalAdvancedOverloadKeepsWarningPolicy() throws Exception {
        Path resources = temporaryDirectory.resolve("resources");
        Files.createDirectories(resources.resolve("defaults"));
        Files.write(resources.resolve("defaults/settings.yml"),
                "name: live\nunknown: allowed\n".getBytes(StandardCharsets.UTF_8));
        ScopeImpl scope = new ScopeImpl("config-module-default-policy");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{resources.toUri().toURL()}, null)) {
            ConfigModule.install(scope, temporaryDirectory.resolve("data"), loader, "defaults",
                    path -> new MigrationRunner());
            assertEquals("live", scope.config(Settings.class, "settings.yml").value().name);
        } finally {
            scope.close();
        }
    }

    @Test
    void nullMapperIsRejectedBeforeInstallingCapability() throws Exception {
        ScopeImpl scope = new ScopeImpl("config-module-null-mapper");
        try (URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
            assertThrows(NullPointerException.class, () -> ConfigModule.install(scope,
                    temporaryDirectory.resolve("data"), loader, "defaults", (YamlConfigMapper) null));
            assertFalse(scope.findCapability(ConfigCapability.class).isPresent());
        } finally {
            scope.close();
        }
    }

    public static final class Settings {
        public String name;
    }
}
