package me.kzheart.klib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.kzheart.klib.random.DoubleRange;
import me.kzheart.klib.random.IntRange;
import org.junit.jupiter.api.Test;

class RangeMappingTest {
    @Test
    void mapsTextNumberAndMinMaxForms() {
        RangeConfig config = read(
                "amount: 1-5\n"
                        + "offset: '-5~-1'\n"
                        + "exact: 3\n"
                        + "level:\n  min: 10\n  max: 20\n"
                        + "bonus: 0.5~2.5\n"
                        + "scale: 2\n");

        assertEquals(IntRange.of(1, 5), config.amount);
        assertEquals(IntRange.of(-5, -1), config.offset);
        assertEquals(IntRange.exactly(3), config.exact);
        assertEquals(IntRange.of(10, 20), config.level);
        assertEquals(DoubleRange.of(0.5, 2.5), config.bonus);
        assertEquals(DoubleRange.exactly(2), config.scale);
        assertEquals(IntRange.of(1, 1), config.untouched);
    }

    @Test
    void invalidRangeReportsLocation() {
        ConfigMappingException failure = assertThrows(ConfigMappingException.class,
                () -> read("amount: 5-1\n"));
        assertTrue(failure.getMessage().contains("range.yml:1:9 (amount)"), failure.getMessage());

        ConfigMappingException missing = assertThrows(ConfigMappingException.class,
                () -> read("level:\n  min: 1\n"));
        assertTrue(missing.getMessage().contains("level.max"), missing.getMessage());

        assertThrows(ConfigMappingException.class, () -> read("exact: 1.5\n"));
    }

    private static RangeConfig read(String yaml) {
        return new YamlConfigMapper().read(YamlDocument.parse("range.yml", yaml).root(), RangeConfig.class);
    }

    static final class RangeConfig {
        private IntRange amount;
        private IntRange offset;
        private IntRange exact;
        private IntRange level;
        private DoubleRange bonus;
        private DoubleRange scale;
        private IntRange untouched = IntRange.of(1, 1);
    }
}
