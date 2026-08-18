package me.kzheart.klib.compat.v26;

import me.kzheart.klib.compat.Capabilities;
import me.kzheart.klib.compat.CompatCapabilityMatrix;
import me.kzheart.klib.compat.CompatProvider;
import me.kzheart.klib.compat.CompatResolver;
import me.kzheart.klib.compat.v1_12.V1_12CompatImplementation;
import me.kzheart.klib.compat.v1_20.V1_20CompatImplementation;
import me.kzheart.klib.compat.v1_21.V1_21CompatImplementation;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatCapabilityMatrixTest {
    @Test
    void allFourBaselineProvidersExposeTheCompleteContract() {
        List<CompatProvider> providers = Arrays.<CompatProvider>asList(
                new V1_12CompatImplementation(),
                new V1_20CompatImplementation(),
                new V1_21CompatImplementation(),
                new V26CompatImplementation()
        );

        CompatCapabilityMatrix matrix = new CompatResolver(providers).capabilityMatrix();

        assertEquals(4, matrix.rows().size());
        for (CompatProvider provider : providers) {
            CompatCapabilityMatrix.Row row = matrix.row(provider.id());
            assertEquals(provider.version(), row.version());
            assertTrue(row.has(Capabilities.TEXT));
            assertTrue(row.has(Capabilities.NBT));
            assertTrue(row.has(Capabilities.MATERIAL));
            assertTrue(row.has(Capabilities.INVENTORY));
        }
    }
}
