package me.kzheart.klib.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基础模块的测试 classpath 上没有任何版本实现，因此这里覆盖“全部缺失”的降级路径；
 * 四个实现同时存在的装配行为由 klib-compat-v26 的跨模块测试覆盖。
 */
class CompatProvidersTest {
    @Test
    void discoveryReturnsEmptyWhenNoImplementationIsBundled() {
        assertTrue(CompatProviders.discover().isEmpty());
        assertEquals(4, CompatProviders.bundledImplementationClassNames().size());
    }

    @Test
    void resolverFailsWithActionableMessageWhenNoImplementationIsBundled() {
        IllegalStateException failure =
                assertThrows(IllegalStateException.class, CompatProviders::resolver);
        assertTrue(failure.getMessage().contains("V1_12CompatImplementation"));
        assertTrue(failure.getMessage().contains("V26CompatImplementation"));

        assertThrows(IllegalStateException.class, () -> CompatProviders.resolve("1.20.4"));
    }

    @Test
    void serverVersionDetectionDegradesWithoutBukkit() {
        assertFalse(CompatProviders.detectServerVersion().isPresent());
        assertThrows(IllegalStateException.class, CompatProviders::resolveCurrent);
    }
}
