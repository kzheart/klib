package me.kzheart.example.empty;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmptyPluginContractTest {
    @Test
    void exposesBukkitEntrypointAndBundledDefaults() throws IOException {
        String defaults = resource("defaults/config.yml");
        String language = resource("lang/zh_CN.yml");

        assertEquals("klibm1", EmptyPlugin.ROOT_COMMAND);
        assertEquals("[klib-m1] ready", EmptyPlugin.READY_MARKER);
        assertTrue(defaults.contains("heartbeatTicks: 200"));
        assertTrue(language.contains("empty:"));
        assertTrue(language.contains("join:"));
        assertTrue(language.contains("status:"));
    }

    @Test
    void settingsHaveSafeJava8Defaults() {
        EmptyPluginSettings settings = new EmptyPluginSettings();
        assertEquals(200L, settings.heartbeatTicks);
    }

    @Test
    void lifecycleProbeIsIdempotentlyObservable() {
        EmptyPlugin.LifecycleProbe probe = new EmptyPlugin.LifecycleProbe();
        assertFalse(probe.isDisposed());
        probe.dispose();
        assertTrue(probe.isDisposed());
    }

    @Test
    void exposesStableMctMessagesAndThirdRebuildMarker() {
        Map<String, Object> help = new LinkedHashMap<String, Object>();
        help.put("page", Integer.valueOf(1));
        help.put("pages", Integer.valueOf(2));

        assertEquals("命令帮助 1/2", EmptyPlugin.commandMessages()
                .resolve(null, me.kzheart.klib.command.CommandMessageKeys.HELP_HEADER, help)
                .plainText());
        assertEquals("配置已重新加载", EmptyPlugin.commandMessages()
                .resolve(
                        null,
                        me.kzheart.klib.command.CommandMessageKeys.BUILTIN_RELOAD_SUCCESS,
                        java.util.Collections.<String, Object>emptyMap())
                .plainText());
        assertEquals("[klib-m1] rebuild=3 resources-ok", EmptyPlugin.lifecycleMarker(4));
    }

    private static String resource(String name) throws IOException {
        InputStream input = EmptyPluginContractTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(input, name);
        try {
            byte[] bytes = new byte[8192];
            int length = input.read(bytes);
            return new String(bytes, 0, length, StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
    }
}
