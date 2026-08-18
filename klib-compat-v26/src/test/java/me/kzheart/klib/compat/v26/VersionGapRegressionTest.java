package me.kzheart.klib.compat.v26;

import me.kzheart.klib.compat.CompatProvider;
import me.kzheart.klib.compat.CompatResolver;
import me.kzheart.klib.compat.v1_12.V1_12CompatImplementation;
import me.kzheart.klib.compat.v1_20.V1_20CompatImplementation;
import me.kzheart.klib.compat.v1_21.V1_21CompatImplementation;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 防止 v1_21 与 v26 基准版本之间出现版本空洞。 */
class VersionGapRegressionTest {
    @Test
    void everyTwentySixLineVersionResolvesToAProvider() {
        List<CompatProvider> providers = Arrays.<CompatProvider>asList(
                new V1_12CompatImplementation(),
                new V1_20CompatImplementation(),
                new V1_21CompatImplementation(),
                new V26CompatImplementation()
        );
        CompatResolver resolver = new CompatResolver(providers);

        assertEquals(V1_21CompatImplementation.ID, resolver.resolve("26.0").id());
        assertEquals(V1_21CompatImplementation.ID, resolver.resolve("26.1").id());
        assertEquals(V26CompatImplementation.ID, resolver.resolve("26.2").id());
        assertEquals(V26CompatImplementation.ID, resolver.resolve("26.3").id());
    }
}
