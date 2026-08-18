package me.kzheart.klib.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UnknownConfigKeyWarningTest {
    private final List<LogRecord> records = new ArrayList<LogRecord>();
    private final Logger logger = Logger.getLogger(YamlConfigMapper.class.getName());
    private Handler handler;

    @BeforeEach
    void attachHandler() {
        handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
    }

    @AfterEach
    void detachHandler() {
        logger.removeHandler(handler);
    }

    @Test
    void misspelledKeyWarnsOnceWithLocation() {
        YamlConfigMapper mapper = new YamlConfigMapper();
        YamlDocument document = YamlDocument.parse(
                "plugins/gather.yml",
                "debug: true\ndatabase:\n  host: localhost\n  prot: 3306\n");

        mapper.read(document.root(), Settings.class);
        mapper.read(document.root(), Settings.class);

        List<LogRecord> warnings = warnings();
        assertEquals(1, warnings.size(), warnings.toString());
        String message = warnings.get(0).getMessage();
        assertTrue(message.contains("plugins/gather.yml:4:3 (database.prot)"), message);
        assertTrue(message.contains("unknown configuration key"), message);
    }

    @Test
    void mapAndListFieldsDoNotWarn() {
        YamlConfigMapper mapper = new YamlConfigMapper();
        YamlDocument document = YamlDocument.parse(
                "plugins/gather.yml",
                "messages:\n  anything: hi\n  other-key: hey\nnames:\n  - a\n  - b\n"
                        + "_schema-version: 3\n");

        mapper.read(document.root(), FreeForm.class);

        assertEquals(0, warnings().size(), warnings().toString());
    }

    @Test
    void unknownKeyInsideMapValuePojoIsReported() {
        YamlConfigMapper mapper = new YamlConfigMapper();
        YamlDocument document = YamlDocument.parse(
                "plugins/gather.yml",
                "arenas:\n  spawn:\n    id: spawn\n    tpye: pvp\n");

        mapper.read(document.root(), Arenas.class);

        List<LogRecord> warnings = warnings();
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).getMessage().contains("(arenas.spawn.tpye)"),
                warnings.get(0).getMessage());
    }

    private List<LogRecord> warnings() {
        List<LogRecord> result = new ArrayList<LogRecord>();
        for (LogRecord record : records) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                result.add(record);
            }
        }
        return result;
    }

    static final class Settings {
        public boolean debug;
        public Database database = new Database();
    }

    static final class Database {
        public String host = "localhost";
        public int port = 3306;
    }

    static final class FreeForm {
        public Map<String, String> messages;
        public List<String> names;
    }

    static final class Arenas {
        public Map<String, Arena> arenas;
    }

    static final class Arena {
        public String id;
        public String type;
    }
}
