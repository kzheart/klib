package me.kzheart.klib.compat.v26;

import me.kzheart.klib.compat.CompatProvider;
import me.kzheart.klib.compat.CompatProviders;
import me.kzheart.klib.compat.CompatResolver;
import me.kzheart.klib.compat.v1_12.V1_12CompatImplementation;
import me.kzheart.klib.compat.v1_20.V1_20CompatImplementation;
import me.kzheart.klib.compat.v1_21.V1_21CompatImplementation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 四个内置实现同时在 classpath 上时的自动装配行为。 */
class CompatProvidersDiscoveryTest {
    @Test
    void discoversEveryBundledImplementationInBaselineOrder() {
        List<String> ids = new ArrayList<String>();
        for (CompatProvider provider : CompatProviders.discover()) {
            ids.add(provider.id());
        }

        assertEquals(Arrays.asList(
                V1_12CompatImplementation.ID,
                V1_20CompatImplementation.ID,
                V1_21CompatImplementation.ID,
                V26CompatImplementation.ID), ids);
    }

    @Test
    void assembledResolverMatchesManualConstruction() {
        CompatResolver resolver = CompatProviders.resolver();

        assertEquals(V1_12CompatImplementation.ID, resolver.resolve("1.12.2").id());
        assertEquals(V1_20CompatImplementation.ID, resolver.resolve("1.20.4").id());
        assertEquals(V1_21CompatImplementation.ID, resolver.resolve("1.21.4").id());
        assertEquals(V26CompatImplementation.ID, resolver.resolve("26.2").id());
        assertEquals(4, resolver.capabilityMatrix().rows().size());
    }

    @Test
    void oneStepEntryPointResolvesFromVersionText() {
        assertEquals(V1_21CompatImplementation.ID,
                CompatProviders.resolve("git-Paper (MC: 1.21.4)").id());
    }
}
