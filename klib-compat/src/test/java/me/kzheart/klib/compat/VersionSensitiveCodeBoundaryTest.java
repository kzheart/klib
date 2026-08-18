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
            "klib-compat-v1_12",
            "klib-compat-v1_20",
            "klib-compat-v1_21",
            "klib-compat-v26"
    ));
    private static final Pattern VERSION_ADAPTER_REFERENCE = Pattern.compile(
            "me\\.kzheart\\.klib\\.compat\\.v(?:1_\\d+|26)(?:\\.|;)");
    private static final Pattern SERVER_INTERNAL_REFERENCE = Pattern.compile(
            "(?:net\\.minecraft|org\\.bukkit\\.craftbukkit)\\.");
    // CompatProviders 的反射发现白名单必须写完整实现类名字面量（重定位器按常量池整串改写），属有意豁免。
    private static final Set<String> EXEMPT_FILES = new HashSet<String>(Arrays.asList(
            "klib-compat/src/main/java/me/kzheart/klib/compat/CompatProviders.java"
    ));

    @Test
    void versionSensitivePackagesAndImportsStayInVersionModules() throws IOException {
        Path root = findRepositoryRoot(Paths.get("").toAbsolutePath());
        List<String> violations = new ArrayList<String>();
        AtomicInteger inspected = new AtomicInteger();

        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> normalize(path).contains("/src/main/java/"))
                    .forEach(path -> {
                        inspected.incrementAndGet();
                        inspect(root, path, violations);
                    });
        }

        assertFalse(inspected.get() == 0, "No production Java files were inspected");
        assertTrue(violations.isEmpty(), "Version-sensitive code escaped compat-v*: " + violations);
    }

    private static void inspect(Path root, Path path, List<String> violations) {
        try {
            String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            boolean sensitive = VERSION_ADAPTER_REFERENCE.matcher(source).find()
                    || SERVER_INTERNAL_REFERENCE.matcher(source).find();
            String relative = root.relativize(path).toString().replace('\\', '/');
            String module = relative.substring(0, relative.indexOf('/'));
            if (sensitive && !VERSION_MODULES.contains(module) && !EXEMPT_FILES.contains(relative)) {
                violations.add(relative);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot inspect " + path, exception);
        }
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
