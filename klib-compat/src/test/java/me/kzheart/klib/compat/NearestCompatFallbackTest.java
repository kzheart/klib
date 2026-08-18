package me.kzheart.klib.compat;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NearestCompatFallbackTest {
    @Test
    void selectsNewestCompatibleBaselineForIntermediateVersions() {
        CompatResolver resolver = new CompatResolver(Arrays.asList(
                provider("compat-v26", "26.2", ServerVersion.of(27, 0, 0)),
                provider("compat-v1_12", "1.12.2", ServerVersion.of(1, 20, 0)),
                provider("compat-v1_21", "1.21", ServerVersion.of(26, 0, 0)),
                provider("compat-v1_20", "1.20.4", ServerVersion.of(1, 21, 0))
        ));

        assertEquals("compat-v1_12", resolver.resolve("1.19.4").id());
        assertEquals("compat-v1_20", resolver.resolve("1.20.6").id());
        assertEquals("compat-v1_21", resolver.resolve("1.21.8").id());
        assertEquals("compat-v1_21", resolver.resolve("25.5").id());
        assertEquals("compat-v26", resolver.resolve("26.3").id());
    }

    @Test
    void failsClosedOutsideDeclaredProviderRanges() {
        CompatResolver resolver = new CompatResolver(Arrays.asList(
                provider("compat-v1_21", "1.21", ServerVersion.of(26, 0, 0))
        ));

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("1.20.6"));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("26.2"));
    }

    @Test
    void neverAppliesANewerProviderToAnOlderServer() {
        CompatResolver resolver = new CompatResolver(Arrays.asList(
                provider("compat-v26", "26.2", ServerVersion.of(27, 0, 0))
        ));

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve("26.1"));
    }

    private static CompatProvider provider(
            final String id,
            String baseline,
            final ServerVersion exclusiveUpperBound
    ) {
        final ServerVersion version = ServerVersion.parse(baseline);
        return new CompatProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public ServerVersion version() {
                return version;
            }

            @Override
            public boolean supports(ServerVersion serverVersion) {
                return serverVersion.compareTo(version) >= 0
                        && serverVersion.compareTo(exclusiveUpperBound) < 0;
            }

            @Override
            public <T> Optional<T> capability(Capability<T> capability) {
                return Optional.empty();
            }
        };
    }
}
