package me.kzheart.klib.data.sql;

import me.kzheart.klib.KLogger;
import me.kzheart.klib.data.StorageException;
import me.kzheart.klib.data.StorageProvider;
import me.kzheart.klib.data.StorageSession;
import me.kzheart.klib.diagnostic.DiagnosticSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

abstract class AbstractJdbcStorageProvider implements StorageProvider, DiagnosticSource {
    /** 网络后端的连接超时，避免防火墙丢包时挂到操作系统的 TCP 超时。 */
    static final int CONNECT_TIMEOUT_MILLIS = 10_000;

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final SqlDialect dialect;
    private final KLogger logger;
    private final Set<JdbcStorageSession> sessions = Collections.synchronizedSet(new HashSet<JdbcStorageSession>());
    private final AtomicBoolean disposed = new AtomicBoolean();

    AbstractJdbcStorageProvider(String jdbcUrl, String username, String password, SqlDialect dialect) {
        this(jdbcUrl, username, password, dialect, null);
    }

    AbstractJdbcStorageProvider(
            String jdbcUrl,
            String username,
            String password,
            SqlDialect dialect,
            KLogger logger
    ) {
        this.jdbcUrl = dialect.applyConnectTimeout(jdbcUrl, CONNECT_TIMEOUT_MILLIS);
        this.username = username;
        this.password = password;
        this.dialect = dialect;
        this.logger = logger;
    }

    @Override
    public CompletionStage<StorageSession> open() {
        if (disposed.get()) {
            CompletableFuture<StorageSession> failed = new CompletableFuture<StorageSession>();
            failed.completeExceptionally(new IllegalStateException("provider is disposed"));
            return failed;
        }
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "klib-storage");
            thread.setDaemon(true);
            return thread;
        });
        return CompletableFuture.supplyAsync(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                JdbcStorageSession session =
                        new JdbcStorageSession(this::createConnection, dialect, executor, sessions::remove, logger);
                sessions.add(session);
                if (disposed.get()) {
                    // 发布后再次检查：并发的 dispose() 可能已清空集合，导致该连接泄漏。
                    sessions.remove(session);
                    session.dispose();
                    throw new IllegalStateException("provider is disposed");
                }
                if (logger != null) {
                    logger.success("已连接 " + dialect.displayName() + " 存储后端，耗时 "
                            + (System.currentTimeMillis() - startedAt) + " 毫秒");
                }
                return (StorageSession) session;
            } catch (SQLException error) {
                executor.shutdownNow();
                String message = "无法连接 " + dialect.displayName() + " 存储后端（耗时 "
                        + (System.currentTimeMillis() - startedAt)
                        + " 毫秒），请检查数据库地址、端口、账号密码与防火墙设置";
                if (logger != null) {
                    logger.error(message, error);
                }
                throw new StorageException(message, error);
            }
        }, executor);
    }

    /** 打开并配置一个连接，供首次打开和重连使用。 */
    private Connection createConnection() throws SQLException {
        Connection connection = username == null
                ? DriverManager.getConnection(jdbcUrl)
                : DriverManager.getConnection(jdbcUrl, username, password);
        try {
            for (String sql : dialect.connectionInit()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
            return connection;
        } catch (SQLException error) {
            try {
                connection.close();
            } catch (SQLException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }

    @Override
    public void dispose() {
        if (!disposed.compareAndSet(false, true)) {
            return;
        }
        JdbcStorageSession[] snapshot;
        synchronized (sessions) {
            snapshot = sessions.toArray(new JdbcStorageSession[0]);
            sessions.clear();
        }
        for (JdbcStorageSession session : snapshot) {
            session.dispose();
        }
    }

    @Override
    public String diagnosticName() {
        return "storage";
    }

    @Override
    public java.util.Map<String, ?> diagnosticSnapshot() {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<String, Object>();
        result.put("backend", dialect.displayName());
        result.put("sessions", sessions.size());
        result.put("closed", disposed.get());
        return result;
    }
}
