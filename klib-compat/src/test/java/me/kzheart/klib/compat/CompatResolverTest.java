package me.kzheart.klib.compat;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatResolverTest {
    @Test
    void selectsExactOrNearestOlderProvider() {
        CompatResolver resolver = new CompatResolver(Arrays.asList(
                provider("compat-v1_20", "1.20.4"),
                provider("compat-v1_12", "1.12.2")
        ));

        assertEquals("compat-v1_12", resolver.resolve("1.12.2").id());
        assertEquals("compat-v1_12", resolver.resolve("1.19.4").id());
        assertEquals("compat-v1_20", resolver.resolve("git-Paper (MC: 1.20.6)").id());
    }

    @Test
    void rejectsVersionsOlderThanAllProviders() {
        CompatResolver resolver = new CompatResolver(Arrays.asList(provider("compat-v1_12", "1.12.2")));
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> resolver.resolve("1.8.8"));
        assertTrue(failure.getMessage().contains("compat-v1_12 (baseline 1.12.2"));
        assertTrue(failure.getMessage().contains("baseline newer than the server"));
    }

    @Test
    void unmatchedNewerVersionReportsMissingSupportDeclaration() {
        CompatResolver resolver = new CompatResolver(Arrays.asList(provider("compat-v1_20", "1.20.4")));
        IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> resolver.resolve("1.21.4"));
        assertTrue(failure.getMessage().contains("does not declare support for this version"));
    }

    @Test
    void rejectsUnsupportedNewerAndDuplicateProviders() {
        CompatResolver resolver = new CompatResolver(Arrays.asList(provider("compat-v1_20", "1.20.4")));

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("26.2"));
        assertThrows(IllegalArgumentException.class, () -> new CompatResolver(Arrays.asList(
                provider("first", "1.20.4"),
                provider("second", "1.20.4"))));
    }

    private static CompatProvider provider(final String id, String version) {
        final ServerVersion parsed = ServerVersion.parse(version);
        return new CompatProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public ServerVersion version() {
                return parsed;
            }

            @Override
            public boolean supports(ServerVersion serverVersion) {
                if (parsed.minor() == 12) {
                    return serverVersion.compareTo(parsed) >= 0
                            && serverVersion.compareTo(ServerVersion.of(1, 20, 0)) < 0;
                }
                return serverVersion.major() == parsed.major()
                        && serverVersion.minor() == parsed.minor();
            }

            @Override
            public <T> Optional<T> capability(Capability<T> capability) {
                return Optional.empty();
            }
        };
    }
}
