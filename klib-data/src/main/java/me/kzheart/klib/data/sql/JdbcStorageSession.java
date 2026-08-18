package me.kzheart.klib.data.sql;

import me.kzheart.klib.KLogger;
import me.kzheart.klib.data.StorageException;
import me.kzheart.klib.data.StorageSession;
import me.kzheart.klib.data.StorageTransaction;
import me.kzheart.klib.data.TransactionContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.Consumer;

final class JdbcStorageSession implements StorageSession {
    private static final int VALIDATION_TIMEOUT_SECONDS = 5;

    private final ConnectionFactory connectionFactory;
    private final SqlDialect dialect;
    private final ExecutorService executor;
    private final Consumer<JdbcStorageSession> onClose;
    private final KLogger logger;
    private final AtomicBoolean disposed = new AtomicBoolean();
    private final Thread executorThread;
    /** 仅限单个执行器线程访问。 */
    private Connection connection;
    /** 仅限单个执行器线程访问；用于抑制重复的重连失败日志。 */
    private boolean reconnectFailureReported;

    interface ConnectionFactory {
        Connection create() throws SQLException;
    }

    JdbcStorageSession(
            ConnectionFactory connectionFactory,
            SqlDialect dialect,
            ExecutorService executor,
            Consumer<JdbcStorageSession> onClose,
            KLogger logger
    ) throws SQLException {
        this.connectionFactory = connectionFactory;
        this.dialect = dialect;
        this.executor = executor;
        this.onClose = onClose;
        this.logger = logger;
        this.executorThread = Thread.currentThread();
        this.connection = connect();
    }

    @Override
    public CompletionStage<Optional<byte[]>> get(String namespace, String key) {
        return readOnly(context -> context.get(namespace, key));
    }

    @Override
    public CompletionStage<Void> put(String namespace, String key, byte[] value) {
        byte[] copy = copy(value);
        return transaction(context -> {
            context.put(namespace, key, copy);
            return null;
        });
    }

    @Override
    public CompletionStage<Void> delete(String namespace, String key) {
        return transaction(context -> {
            context.delete(namespace, key);
            return null;
        });
    }

    @Override
    public CompletionStage<Map<String, byte[]>> entries(String namespace) {
        return readOnly(context -> context.entries(namespace));
    }

    @Override
    public <T> CompletionStage<T> transaction(StorageTransaction<T> transaction) {
        if (transaction == null) {
            throw new NullPointerException("transaction");
        }
        return submit(() -> execute(transaction, true));
    }

    /** 单语句读取跳过显式事务及其额外往返。 */
    private <T> CompletionStage<T> readOnly(StorageTransaction<T> operation) {
        return submit(() -> execute(operation, false));
    }

    private <T> T execute(StorageTransaction<T> transaction, boolean transactional) {
        try {
            try {
                return run(transaction, transactional);
            } catch (Exception error) {
                if (!transactional && shouldRetry(error)) {
                    // 底层连接已失效：重建连接并重试一次。
                    warn("数据库连接已失效，正在重建连接并重试一次读取：" + describe(error));
                    resetConnection();
                    T retried = run(transaction, transactional);
                    if (logger != null) {
                        logger.success("数据库连接已恢复");
                    }
                    return retried;
                }
                throw error;
            }
        } catch (Exception error) {
            throw error instanceof StorageException
                    ? (StorageException) error
                    : new StorageException("Storage transaction failed", error);
        }
    }

    private void warn(String message) {
        if (logger != null) {
            logger.warn(message);
        }
    }

    private static String describe(Throwable error) {
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }

    private <T> T run(StorageTransaction<T> transaction, boolean transactional) throws Exception {
        return transactional ? runTransaction(transaction) : runReadOnly(transaction);
    }

    private <T> T runTransaction(StorageTransaction<T> transaction) throws Exception {
        Connection active = ensureConnection();
        boolean previousAutoCommit = active.getAutoCommit();
        active.setAutoCommit(false);
        T result;
        try {
            result = transaction.execute(new JdbcTransactionContext(active, dialect));
            active.commit();
        } catch (Exception error) {
            try {
                active.rollback();
            } catch (SQLException rollbackError) {
                error.addSuppressed(rollbackError);
            }
            try {
                active.setAutoCommit(previousAutoCommit);
            } catch (SQLException restoreError) {
                error.addSuppressed(restoreError);
            }
            throw error;
        }
        active.setAutoCommit(previousAutoCommit);
        return result;
    }

    private <T> T runReadOnly(StorageTransaction<T> operation) throws Exception {
        return operation.execute(new JdbcTransactionContext(ensureConnection(), dialect));
    }

    private Connection ensureConnection() throws SQLException {
        if (connection != null) {
            try {
                if (!connection.isClosed() && connection.isValid(VALIDATION_TIMEOUT_SECONDS)) {
                    return connection;
                }
            } catch (SQLException ignored) {
                // 将有效性检查失败视为连接已失效。
            }
            warn("数据库连接已断开，正在自动重连");
            closeQuietly(connection);
            connection = null;
        }
        connection = reconnect();
        return connection;
    }

    /**
     * 重连并汇报结果。连续失败只记录第一次，避免数据库长时间不可用时，
     * 每一条被拒绝的读写都往控制台打一份堆栈。
     */
    private Connection reconnect() throws SQLException {
        try {
            Connection reconnected = connect();
            if (logger != null && reconnectFailureReported) {
                logger.success("数据库自动重连成功");
            }
            reconnectFailureReported = false;
            return reconnected;
        } catch (SQLException error) {
            if (logger != null && !reconnectFailureReported) {
                logger.error("数据库自动重连失败，后续读写都会失败，请检查数据库服务与网络", error);
            }
            reconnectFailureReported = true;
            throw error;
        }
    }

    private Connection connect() throws SQLException {
        Connection created = connectionFactory.create();
        try (Statement statement = created.createStatement()) {
            statement.execute(dialect.createKv());
            statement.execute(dialect.createSchema());
            return created;
        } catch (SQLException error) {
            try {
                created.close();
            } catch (SQLException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }

    private void resetConnection() {
        if (connection != null) {
            closeQuietly(connection);
            connection = null;
        }
    }

    private boolean shouldRetry(Exception error) {
        if (!hasSqlCause(error)) {
            return false;
        }
        if (connection == null) {
            return true;
        }
        try {
            return connection.isClosed() || !connection.isValid(VALIDATION_TIMEOUT_SECONDS);
        } catch (SQLException ignored) {
            return true;
        }
    }

    private static boolean hasSqlCause(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException) {
                return true;
            }
        }
        return false;
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 连接已经损坏，无需继续处理。
        }
    }

    private <T> CompletionStage<T> submit(Supplier<T> supplier) {
        if (disposed.get()) {
            CompletableFuture<T> failed = new CompletableFuture<T>();
            failed.completeExceptionally(new IllegalStateException("session is disposed"));
            return failed;
        }
        return CompletableFuture.supplyAsync(supplier, executor);
    }

    @Override
    public void dispose() {
        if (!disposed.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture<Void> closed = CompletableFuture.runAsync(() -> {
            try {
                if (connection != null) {
                    connection.close();
                    connection = null;
                }
            } catch (SQLException error) {
                throw new StorageException("Could not close JDBC storage", error);
            }
        }, executor).whenComplete((ignored, error) -> {
            if (error != null && logger != null) {
                logger.error("关闭数据库连接时发生异常", error);
            }
            executor.shutdown();
            onClose.accept(this);
        });
        if (Thread.currentThread() == executorThread) {
            return;
        }
        try {
            closed.get(30L, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new StorageException("Interrupted while closing JDBC storage", interrupted);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            throw cause instanceof StorageException
                    ? (StorageException) cause
                    : new StorageException("Could not close JDBC storage", cause);
        } catch (TimeoutException timeout) {
            throw new StorageException("Timed out closing JDBC storage", timeout);
        }
    }

    private static byte[] copy(byte[] value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        return Arrays.copyOf(value, value.length);
    }

    private static final class JdbcTransactionContext implements TransactionContext {
        private final Connection connection;
        private final SqlDialect dialect;

        private JdbcTransactionContext(Connection connection, SqlDialect dialect) {
            this.connection = connection;
            this.dialect = dialect;
        }

        @Override
        public Optional<byte[]> get(String namespace, String key) {
            requireName(namespace, "namespace");
            requireName(key, "key");
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT entry_value FROM klib_kv WHERE namespace = ? AND entry_key = ?")) {
                statement.setString(1, namespace);
                statement.setString(2, key);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(copy(result.getBytes(1))) : Optional.empty();
                }
            } catch (SQLException error) {
                throw new StorageException("Could not read key", error);
            }
        }

        @Override
        public void put(String namespace, String key, byte[] value) {
            requireName(namespace, "namespace");
            requireName(key, "key");
            try (PreparedStatement statement = connection.prepareStatement(dialect.upsertKv())) {
                statement.setString(1, namespace);
                statement.setString(2, key);
                statement.setBytes(3, copy(value));
                statement.executeUpdate();
            } catch (SQLException error) {
                throw new StorageException("Could not write key", error);
            }
        }

        @Override
        public void delete(String namespace, String key) {
            requireName(namespace, "namespace");
            requireName(key, "key");
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM klib_kv WHERE namespace = ? AND entry_key = ?")) {
                statement.setString(1, namespace);
                statement.setString(2, key);
                statement.executeUpdate();
            } catch (SQLException error) {
                throw new StorageException("Could not delete key", error);
            }
        }

        @Override
        public Map<String, byte[]> entries(String namespace) {
            requireName(namespace, "namespace");
            Map<String, byte[]> values = new LinkedHashMap<String, byte[]>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT entry_key, entry_value FROM klib_kv WHERE namespace = ? ORDER BY entry_key")) {
                statement.setString(1, namespace);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        values.put(result.getString(1), copy(result.getBytes(2)));
                    }
                }
                return values;
            } catch (SQLException error) {
                throw new StorageException("Could not list keys", error);
            }
        }

        @Override
        public int schemaVersion(String schemaName) {
            requireName(schemaName, "schemaName");
            try (PreparedStatement statement = connection.prepareStatement(dialect.selectSchemaForUpdate())) {
                statement.setString(1, schemaName);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getInt(1) : 0;
                }
            } catch (SQLException error) {
                throw new StorageException("Could not read schema version", error);
            }
        }

        @Override
        public void schemaVersion(String schemaName, int version) {
            requireName(schemaName, "schemaName");
            try (PreparedStatement statement = connection.prepareStatement(dialect.upsertSchema())) {
                statement.setString(1, schemaName);
                statement.setInt(2, version);
                statement.executeUpdate();
            } catch (SQLException error) {
                throw new StorageException("Could not write schema version", error);
            }
        }

        private static void requireName(String value, String label) {
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException(label + " must not be empty");
            }
        }
    }
}
