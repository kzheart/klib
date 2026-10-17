package me.kzheart.klib.data.jdbc;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/** 只在隔离 SQLite 测试内计数和注入 JDBC 故障。 */
final class JdbcTestDataSource implements DataSource {
    final AtomicInteger opened = new AtomicInteger();
    final AtomicInteger closed = new AtomicInteger();
    final AtomicInteger statements = new AtomicInteger();
    final AtomicInteger rows = new AtomicInteger();
    final AtomicInteger commits = new AtomicInteger();
    final AtomicInteger rollbacks = new AtomicInteger();
    final AtomicInteger aborts = new AtomicInteger();
    final AtomicInteger autoCommitRestores = new AtomicInteger();
    final DataSource delegate;
    volatile boolean failCommit;
    volatile boolean failRollback;
    volatile boolean failClose;
    volatile boolean failRestore;
    volatile boolean failAcquire;
    volatile boolean suppliedAutoCommit = true;
    volatile boolean closedAutoCommit;
    volatile int closedIsolation;

    JdbcTestDataSource(Path file) {
        delegate = JdbcDataSources.driver("org.sqlite.JDBC", getClass().getClassLoader(),
                "jdbc:sqlite:" + file, new Properties());
    }

    @Override public Connection getConnection() throws SQLException {
        if (failAcquire) throw new SQLException("acquisition failed");
        Connection actual = delegate.getConnection();
        opened.incrementAndGet();
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getAutoCommit") && !suppliedAutoCommit) return false;
                    if (name.equals("commit")) {
                        commits.incrementAndGet();
                        if (failCommit) throw new SQLException("lost commit reply");
                    }
                    if (name.equals("rollback")) {
                        rollbacks.incrementAndGet();
                        if (failRollback) throw new SQLException("rollback failed");
                    }
                    if (name.equals("setAutoCommit") && Boolean.TRUE.equals(args[0])) {
                        autoCommitRestores.incrementAndGet();
                        if (failRestore) throw new SQLException("restore failed");
                    }
                    if (name.equals("abort")) { aborts.incrementAndGet(); return null; }
                    if (name.equals("close")) {
                        closedAutoCommit = actual.getAutoCommit();
                        closedIsolation = actual.getTransactionIsolation();
                        try { actual.close(); } finally { closed.incrementAndGet(); }
                        if (failClose) throw new SQLException("close failed");
                        return null;
                    }
                    Object result = invoke(actual, method, args);
                    if (name.equals("prepareStatement")) return statement((PreparedStatement) result);
                    return result;
                });
    }

    private PreparedStatement statement(PreparedStatement actual) {
        statements.incrementAndGet();
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        try { return invoke(actual, method, args); } finally { statements.decrementAndGet(); }
                    }
                    Object result = invoke(actual, method, args);
                    if (result instanceof ResultSet) return resultSet((ResultSet) result);
                    return result;
                });
    }

    private ResultSet resultSet(ResultSet actual) {
        rows.incrementAndGet();
        return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ResultSet.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        try { return invoke(actual, method, args); } finally { rows.decrementAndGet(); }
                    }
                    return invoke(actual, method, args);
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }

    @Override public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }
    @Override public PrintWriter getLogWriter() { return null; }
    @Override public void setLogWriter(PrintWriter value) { }
    @Override public void setLoginTimeout(int value) { }
    @Override public int getLoginTimeout() { return 0; }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
    @Override public <T> T unwrap(Class<T> type) throws SQLException { throw new SQLException("not a wrapper"); }
    @Override public boolean isWrapperFor(Class<?> type) { return false; }
}
