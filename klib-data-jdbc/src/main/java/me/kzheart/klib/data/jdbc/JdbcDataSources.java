package me.kzheart.klib.data.jdbc;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/** JDBC 数据源入口；驱动由调用方指定的类加载器提供，不查询 JVM 全局驱动注册表。 */
public final class JdbcDataSources {
    private JdbcDataSources() { }

    /** 创建不带连接池的数据源；驱动在首次连接时加载，属性以防御性副本保存。 */
    public static DataSource driver(String driverClass, ClassLoader loader, String url, Properties properties) {
        if (driverClass == null || driverClass.trim().isEmpty() || loader == null
                || url == null || url.trim().isEmpty() || properties == null) {
            throw new IllegalArgumentException("driverClass, loader, url and properties are required");
        }
        return new DriverSource(driverClass, loader, url, properties);
    }

    private static final class DriverSource implements DataSource {
        private final String driverClass;
        private final ClassLoader loader;
        private final String url;
        private final Properties properties = new Properties();
        private volatile Driver driver;
        private volatile PrintWriter writer;

        private DriverSource(String driverClass, ClassLoader loader, String url, Properties properties) {
            this.driverClass = driverClass;
            this.loader = loader;
            this.url = url;
            for (String name : properties.stringPropertyNames()) {
                this.properties.setProperty(name, properties.getProperty(name));
            }
        }

        private Driver driver() throws SQLException {
            Driver current = driver;
            if (current != null) return current;
            synchronized (this) {
                if (driver == null) {
                    try {
                        driver = Class.forName(driverClass, true, loader).asSubclass(Driver.class)
                                .getDeclaredConstructor().newInstance();
                    } catch (ReflectiveOperationException | ClassCastException failure) {
                        throw new SQLException("JDBC driver unavailable: " + driverClass, failure);
                    }
                }
                return driver;
            }
        }

        @Override public Connection getConnection() throws SQLException {
            Properties copy = new Properties();
            copy.putAll(properties);
            return connect(copy);
        }

        @Override public Connection getConnection(String user, String password) throws SQLException {
            Properties copy = new Properties();
            copy.putAll(properties);
            if (user == null) copy.remove("user"); else copy.setProperty("user", user);
            if (password == null) copy.remove("password"); else copy.setProperty("password", password);
            return connect(copy);
        }

        private Connection connect(Properties copy) throws SQLException {
            Connection connection = driver().connect(url, copy);
            if (connection == null) throw new SQLException("JDBC driver does not support the configured URL");
            return connection;
        }

        @Override public PrintWriter getLogWriter() { return writer; }
        @Override public void setLogWriter(PrintWriter writer) { this.writer = writer; }
        @Override public int getLoginTimeout() { return 0; }
        @Override public void setLoginTimeout(int seconds) throws SQLException {
            if (seconds != 0) throw new SQLFeatureNotSupportedException("Configure the driver's connect timeout");
        }
        @Override public Logger getParentLogger() { return Logger.getLogger("me.kzheart.klib.data.jdbc"); }
        @Override public boolean isWrapperFor(Class<?> type) { return type != null && type.isInstance(this); }
        @Override public <T> T unwrap(Class<T> type) throws SQLException {
            if (!isWrapperFor(type)) throw new SQLException("Not a wrapper for the requested type");
            return type.cast(this);
        }
    }
}
