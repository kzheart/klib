package me.kzheart.klib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DurationCompoundTest {
    @Test
    void parsesCompoundHumanDuration() {
        DurationConfig config = new YamlConfigMapper().read(
                YamlDocument.parse("duration.yml", "timeout: 1h30m15s250ms\n").root(),
                DurationConfig.class);

        assertEquals(
                Duration.ofHours(1).plusMinutes(30).plusSeconds(15).plusMillis(250),
                config.timeout);
    }

    static final class DurationConfig {
        private Duration timeout;
    }
}
