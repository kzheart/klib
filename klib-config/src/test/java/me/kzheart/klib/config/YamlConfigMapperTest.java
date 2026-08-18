package me.kzheart.klib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.kzheart.klib.scope.ScopeImpl;
import org.junit.jupiter.api.Test;

class YamlConfigMapperTest {
    @Test
    void mapsNestedPojoCollectionsEnumsDurationsAndCustomConverters() {
        YamlDocument document = YamlDocument.parse(
                "typed.yml",
                "name: demo\n"
                        + "mode: fast\n"
                        + "timeout: 1500ms\n"
                        + "ports: [25565, 25566]\n"
                        + "labels:\n  primary: live\n"
                        + "endpoint: example.test:8443\n");
        YamlConfigMapper mapper = new YamlConfigMapper()
                .registerConverter(Endpoint.class, node -> {
                    String[] parts = ((String) node.raw()).split(":");
                    return new Endpoint(parts[0], Integer.parseInt(parts[1]));
                });

        TypedConfig result = mapper.read(document.root(), TypedConfig.class);

        assertEquals("demo", result.name);
        assertEquals(Mode.FAST, result.mode);
        assertEquals(Duration.ofMillis(1500), result.timeout);
        assertEquals(Arrays.asList(25565, 25566), result.ports);
        assertEquals("live", result.labels.get("primary"));
        assertEquals("example.test", result.endpoint.host);
        assertEquals(8443, result.endpoint.port);
    }

    @Test
    void coreCapabilityCanLoadFromInMemoryYaml() {
        Map<String, String> documents = new LinkedHashMap<String, String>();
        documents.put("config.yml", "name: from-scope\n");
        ScopeImpl scope = new ScopeImpl("config-test");
        scope.registerCapability(
                me.kzheart.klib.scope.capability.ConfigCapability.class,
                YamlConfigCapability.inMemory(documents));

        me.kzheart.klib.config.api.ConfigDocument<TypedConfig> document =
                scope.config(TypedConfig.class, "config.yml");

        assertEquals("from-scope", document.value().name);
    }

    enum Mode {
        FAST,
        SAFE
    }

    static final class TypedConfig {
        private String name = "default";
        private Mode mode = Mode.SAFE;
        private Duration timeout = Duration.ofSeconds(1);
        private List<Integer> ports;
        private Map<String, String> labels;
        private Endpoint endpoint;
    }

    static final class Endpoint {
        private final String host;
        private final int port;

        Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }
}
