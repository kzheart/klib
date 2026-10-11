package me.kzheart.klib.data.jdbc;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class JdbcDataSourcesTest {
    @Test void bindsRequestedDriverAndProtectsConnectionProperties() throws Exception {
        Driver foreign = new DriverImpl() {
            @Override public Connection connect(String url, Properties properties) { fail("Foreign driver selected"); return null; }
        };
        DriverManager.registerDriver(foreign);
        try {
            Properties properties = new Properties(); properties.setProperty("user", "original");
            DataSource source = JdbcDataSources.driver(DriverImpl.class.getName(), getClass().getClassLoader(), "jdbc:fixture:test", properties);
            properties.setProperty("user", "changed");
            try (Connection connection = source.getConnection()) { assertEquals("original", connection.getClientInfo("user")); }
            try (Connection connection = source.getConnection("override", "secret")) { assertEquals("override", connection.getClientInfo("user")); }
            try (Connection connection = source.getConnection()) { assertEquals("original", connection.getClientInfo("user")); }
        } finally { DriverManager.deregisterDriver(foreign); }
    }
    @Test void unsupportedUrlAndMissingDriverFailWithoutUsingGlobalRegistry() {
        assertThrows(SQLException.class, () -> JdbcDataSources.driver(DriverImpl.class.getName(), getClass().getClassLoader(), "jdbc:other:test", new Properties()).getConnection());
        assertThrows(SQLException.class, () -> JdbcDataSources.driver("missing.Driver", getClass().getClassLoader(), "jdbc:fixture:test", new Properties()).getConnection());
    }
    public static class DriverImpl implements Driver {
        public DriverImpl() { }
        @Override public Connection connect(String url, Properties properties) {
            if (!acceptsURL(url)) return null;
            String user = properties.getProperty("user"); properties.setProperty("user", "driver mutation");
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("getClientInfo")) return user;
                if (method.getName().equals("close")) return null;
                throw new UnsupportedOperationException(method.getName());
            });
        }
        @Override public boolean acceptsURL(String url) { return url.startsWith("jdbc:fixture:"); }
        @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
        @Override public int getMajorVersion() { return 1; }
        @Override public int getMinorVersion() { return 0; }
        @Override public boolean jdbcCompliant() { return false; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
    }
}
