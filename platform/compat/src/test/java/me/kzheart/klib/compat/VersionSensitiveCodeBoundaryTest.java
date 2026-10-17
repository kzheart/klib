package me.kzheart.klib.compat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionSensitiveCodeBoundaryTest {
    private static final Set<String> VERSION_MODULES = new HashSet<String>(Arrays.asList(
            "platform/versions/v1_12",
            "platform/versions/v1_20",
            "platform/versions/v1_21",
            "platform/versions/v26"
    ));
    private static final Pattern VERSION_ADAPTER_REFERENCE = Pattern.compile(
            "me\\.kzheart\\.klib\\.compat\\.v(?:1_\\d+|26)(?:\\.|;)");
    private static final Pattern SERVER_INTERNAL_REFERENCE = Pattern.compile(
            "(?:net\\.minecraft|org\\.bukkit\\.craftbukkit)\\.");
    // CompatProviders 的反射发现白名单必须写完整实现类名字面量（重定位器按常量池整串改写），属有意豁免。
    private static final Set<String> EXEMPT_FILES = new HashSet<String>(Arrays.asList(
            "platform/compat/src/main/kotlin/me/kzheart/klib/compat/CompatProviders.kt",
            // Shared packet shape lives in compat; concrete providers still select version ranges.
            "platform/compat/src/main/kotlin/me/kzheart/klib/compat/internal/ModernSidebarBridge.kt",
            // Native tag layout discovery is one contained adapter; no NMS type escapes its API.
            "libraries/item/src/main/kotlin/me/kzheart/klib/item/tag/NativeItemTags.kt"
    ));

    @Test
    void versionSensitivePackagesAndImportsStayInVersionModules() throws IOException {
        Path root = findRepositoryRoot(Paths.get("").toAbsolutePath());
        List<String> violations = new ArrayList<String>();
        AtomicInteger inspected = new AtomicInteger();
        for (String module : VERSION_MODULES) {
            assertTrue(Files.isDirectory(root.resolve(module).resolve("src/main")),
                    "Version module is missing from the grouped layout: " + module);
        }

        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> path.toString().endsWith(".java") || path.toString().endsWith(".kt"))
                    .filter(path -> normalize(path).contains("/src/main/java/") || normalize(path).contains("/src/main/kotlin/"))
                    .forEach(path -> {
                        inspected.incrementAndGet();
                        inspect(root, path, violations);
                    });
        }

        assertFalse(inspected.get() == 0, "No production Java/Kotlin files were inspected");
        assertTrue(violations.isEmpty(), "Version-sensitive code escaped compat-v*: " + violations);
    }

    private static void inspect(Path root, Path path, List<String> violations) {
        try {
            String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            boolean sensitive = VERSION_ADAPTER_REFERENCE.matcher(source).find()
                    || SERVER_INTERNAL_REFERENCE.matcher(source).find();
            String relative = root.relativize(path).toString().replace('\\', '/');
            if (sensitive && !isVersionModuleSource(relative) && !EXEMPT_FILES.contains(relative)) {
                violations.add(relative);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot inspect " + path, exception);
        }
    }

    @Test
    void groupedVersionAllowlistDoesNotExemptOtherPlatformOrLibrarySources() {
        for (String module : VERSION_MODULES) {
            assertTrue(isVersionModuleSource(module + "/src/main/kotlin/Bridge.kt"));
            assertTrue(isVersionModuleSource(module + "/src/main/java/Bridge.java"));
            assertFalse(isVersionModuleSource(module + "-other/src/main/kotlin/Bridge.kt"));
            assertFalse(isVersionModuleSource(module + "/src/test/java/BridgeTest.java"));
        }
        assertFalse(isVersionModuleSource("platform/compat/src/main/kotlin/Bridge.kt"));
        assertFalse(isVersionModuleSource("platform/versions/unknown/src/main/kotlin/Bridge.kt"));
        assertFalse(isVersionModuleSource("libraries/core/src/main/kotlin/Bridge.kt"));
    }

    private static boolean isVersionModuleSource(String relative) {
        for (String module : VERSION_MODULES) {
            if (relative.startsWith(module + "/src/main/java/")
                    || relative.startsWith(module + "/src/main/kotlin/")) {
                return true;
            }
        }
        return false;
    }

    private static Path findRepositoryRoot(Path start) {
        Path current = start;
        while (current != null
                && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Cannot locate repository root from " + start);
        }
        return current;
    }

    private static String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }
}
