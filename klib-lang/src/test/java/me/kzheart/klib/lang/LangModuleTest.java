package me.kzheart.klib.lang;

import me.kzheart.klib.scope.ScopeImpl;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangModuleTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void installsNormalizedLocaleAndExtractsBundledDefaults() throws Exception {
        ScopeImpl scope = new ScopeImpl("lang-test");
        try {
            LangRuntime runtime = install(scope, "zh-cn");

            assertEquals("zh_CN", runtime.locale());
            assertEquals(
                    "<red>你没有权限执行此操作</red>",
                    runtime.catalog().find(BuiltinMessages.NO_PERMISSION).get());
            assertTrue(Files.isRegularFile(temporaryDirectory.resolve("lang/zh_CN.yml")));
        } finally {
            scope.close();
        }
    }

    @Test
    void registersRuntimeAsScopeCapability() throws Exception {
        ScopeImpl scope = new ScopeImpl("lang-capability-test");
        try {
            LangRuntime runtime = install(scope, "zh_CN");

            assertSame(runtime, scope.requireCapability(LangRuntime.class));
        } finally {
            scope.close();
        }
    }

    @Test
    void reloadsConfiguredMessagesAndKeepsBuiltinFallback() throws Exception {
        ScopeImpl scope = new ScopeImpl("lang-reload-test");
        LangRuntime runtime = install(scope, "zh_CN");
        Path languageFile = temporaryDirectory.resolve("lang/zh_CN.yml");
        Files.write(
                languageFile,
                ("messages:\n"
                        + "  custom: changed\n").getBytes(StandardCharsets.UTF_8));

        runtime.configDocument().reload();

        assertEquals("changed", runtime.catalog().find("custom").get());
        assertTrue(runtime.catalog().find(BuiltinMessages.NO_PERMISSION).isPresent());
        scope.close();
        assertThrows(IllegalStateException.class, runtime::catalog);
    }

    @Test
    void fallsBackToDefaultLocaleFileWhenResourceIsMissing() throws Exception {
        ScopeImpl scope = new ScopeImpl("lang-fallback-test");
        try {
            LangRuntime runtime = install(scope, "fr_FR");

            assertEquals("zh_CN", runtime.locale());
            assertTrue(Files.isRegularFile(temporaryDirectory.resolve("lang/zh_CN.yml")));
            assertFalse(Files.exists(temporaryDirectory.resolve("lang/fr_FR.yml")));
            assertEquals(
                    "<red>你没有权限执行此操作</red>",
                    runtime.catalog().find(BuiltinMessages.NO_PERMISSION).get());
        } finally {
            scope.close();
        }
    }

    @Test
    void rejectsUnsafeLocaleNames() {
        ScopeImpl scope = new ScopeImpl("lang-invalid-test");
        try {
            assertThrows(IllegalArgumentException.class, () -> install(scope, "../../secret"));
        } finally {
            scope.close();
        }
    }

    private LangRuntime install(ScopeImpl scope, String locale) {
        Server server = (Server) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, arguments) -> defaultValue(method.getReturnType()));
        return LangModule.install(
                scope,
                server,
                temporaryDirectory,
                getClass().getClassLoader(),
                locale,
                null);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (type == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (type == Long.TYPE) {
            return Long.valueOf(0L);
        }
        if (type == Double.TYPE) {
            return Double.valueOf(0.0D);
        }
        if (type == Float.TYPE) {
            return Float.valueOf(0.0F);
        }
        if (type == Short.TYPE) {
            return Short.valueOf((short) 0);
        }
        if (type == Byte.TYPE) {
            return Byte.valueOf((byte) 0);
        }
        if (type == Character.TYPE) {
            return Character.valueOf('\0');
        }
        return null;
    }
}
