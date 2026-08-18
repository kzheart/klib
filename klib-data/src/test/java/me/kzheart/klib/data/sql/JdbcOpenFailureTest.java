package me.kzheart.klib.data.sql;

import me.kzheart.klib.KLogger;
import me.kzheart.klib.data.StorageProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcOpenFailureTest {
    @Test
    void schemaInitializationFailureClosesCreatedConnection() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        Driver driver = new FailingSchemaDriver(closed);
        DriverManager.registerDriver(driver);
        try {
            StorageProvider provider = new FailingProvider();
            assertThrows(ExecutionException.class, () -> provider.open().toCompletableFuture().get());
            assertTrue(closed.get());
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }

    @Test
    void openFailureIsReportedThroughLogger() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        Driver driver = new FailingSchemaDriver(closed);
        DriverManager.registerDriver(driver);
        KLogger logger = new KLogger(Logger.getLogger("klib-data-test"));
        try {
            StorageProvider provider = new FailingProvider(logger);
            assertThrows(ExecutionException.class, () -> provider.open().toCompletableFuture().get());
        } finally {
            DriverManager.deregisterDriver(driver);
        }
        List<String> lines = logger.recentLines();
        assertFalse(lines.isEmpty());
        String last = lines.get(lines.size() - 1);
        assertTrue(last.contains("无法连接 SQLite 存储后端"), last);
        assertTrue(last.contains("防火墙"), last);
    }

    @Test
    void mysqlUrlGainsConnectTimeoutWhileSqliteIsUnchanged() {
        assertEquals(
                "jdbc:mysql://host/db?connectTimeout=10000",
                SqlDialect.MYSQL.applyConnectTimeout("jdbc:mysql://host/db", 10_000));
        assertEquals(
                "jdbc:mysql://host/db?useSSL=false&connectTimeout=10000",
                SqlDialect.MYSQL.applyConnectTimeout("jdbc:mysql://host/db?useSSL=false", 10_000));
        assertEquals(
                "jdbc:mysql://host/db?connectTimeout=1",
                SqlDialect.MYSQL.applyConnectTimeout("jdbc:mysql://host/db?connectTimeout=1", 10_000));
        assertEquals(
                "jdbc:sqlite:/tmp/data.db",
                SqlDialect.SQLITE.applyConnectTimeout("jdbc:sqlite:/tmp/data.db", 10_000));
        assertEquals(
                "jdbc:h2:mem:contract;MODE=MySQL",
                SqlDialect.MYSQL.applyConnectTimeout("jdbc:h2:mem:contract;MODE=MySQL", 10_000));
    }

    private static final class FailingProvider extends AbstractJdbcStorageProvider {
        private FailingProvider() {
            super("jdbc:klib-fail:schema", null, null, SqlDialect.SQLITE);
        }

        private FailingProvider(KLogger logger) {
            super("jdbc:klib-fail:schema", null, null, SqlDialect.SQLITE, logger);
        }
    }

    private static final class FailingSchemaDriver implements Driver {
        private final AtomicBoolean closed;

        private FailingSchemaDriver(AtomicBoolean closed) {
            this.closed = closed;
        }

        @Override
        public Connection connect(String url, Properties info) {
            if (!acceptsURL(url)) {
                return null;
            }
            Statement statement = (Statement) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{Statement.class},
                    (proxy, method, args) -> {
                        if ("execute".equals(method.getName())) {
                            throw new SQLException("schema failure");
                        }
                        return defaultValue(method.getReturnType());
                    });
            return (Connection) Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if ("createStatement".equals(method.getName())) {
                            return statement;
                        }
                        if ("close".equals(method.getName())) {
                            closed.set(true);
                            return null;
                        }
                        if ("isClosed".equals(method.getName())) {
                            return closed.get();
                        }
                        return defaultValue(method.getReturnType());
                    });
        }

        @Override
        public boolean acceptsURL(String url) {
            return url != null && url.startsWith("jdbc:klib-fail:");
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
