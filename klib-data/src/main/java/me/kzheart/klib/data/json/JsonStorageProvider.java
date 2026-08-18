package me.kzheart.klib.data.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import me.kzheart.klib.data.StorageException;
import me.kzheart.klib.data.StorageProvider;
import me.kzheart.klib.data.StorageSession;
import me.kzheart.klib.diagnostic.DiagnosticSource;
import me.kzheart.klib.data.StorageTransaction;
import me.kzheart.klib.data.TransactionContext;

import java.io.BufferedWriter;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 事务式文件 JSON 存储。提供器为每个文件持有一份共享状态和一个串行执行器，
 * 因此同一提供器打开的多个会话是相同数据的视图，不会互相覆盖。
 */
public final class JsonStorageProvider implements StorageProvider, DiagnosticSource {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final long MAX_FILE_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_JSON_DEPTH = 16;
    private static final int MAX_NAMESPACES = 128;
    private static final int MAX_ENTRIES = 4096;
    private static final int MAX_NAMESPACE_BYTES = 128;
    private static final int MAX_KEY_BYTES = 512;
    private static final int MAX_VALUE_BYTES = 1024 * 1024;
    private static final long MAX_TOTAL_VALUE_BYTES = 4L * 1024L * 1024L;
    private static final int MAX_SCHEMAS = 256;

    private final Object lock = new Object();
    private final Path file;
    private final Set<JsonStorageSession> sessions = Collections.newSetFromMap(new ConcurrentHashMap<JsonStorageSession, Boolean>());
    private boolean disposed;
    private ExecutorService executor;
    private volatile Thread executorThread;
    /** 仅在串行执行器线程中加载和修改；使用 volatile 以便在执行器重启时交接。 */
    private volatile State state;

    public JsonStorageProvider(Path file) {
        if (file == null) {
            throw new NullPointerException("file");
        }
        this.file = file.toAbsolutePath();
    }

    @Override
    public CompletionStage<StorageSession> open() {
        ExecutorService active;
        synchronized (lock) {
            if (disposed) {
                return failed(new IllegalStateException("provider is disposed"));
            }
            active = executor();
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (state == null) {
                    state = read(file);
                }
                JsonStorageSession session = new JsonStorageSession();
                sessions.add(session);
                synchronized (lock) {
                    if (disposed) {
                        // dispose() 可能已经并发清空该集合。
                        sessions.remove(session);
                        throw new IllegalStateException("provider is disposed");
                    }
                }
                return (StorageSession) session;
            } catch (RuntimeException error) {
                shutdownIfUnused();
                throw error;
            }
        }, active);
    }

    @Override
    public void dispose() {
        synchronized (lock) {
            if (disposed) {
                return;
            }
            disposed = true;
        }
        for (JsonStorageSession session : sessions) {
            session.dispose();
        }
        sessions.clear();
        ExecutorService closing;
        synchronized (lock) {
            closing = executor;
            executor = null;
        }
        if (closing != null) {
            closing.shutdown();
            if (Thread.currentThread() != executorThread) {
                try {
                    if (!closing.awaitTermination(30L, TimeUnit.SECONDS)) {
                        closing.shutdownNow();
                        throw new StorageException("Timed out closing JSON storage");
                    }
                } catch (InterruptedException interrupted) {
                    closing.shutdownNow();
                    Thread.currentThread().interrupt();
                    throw new StorageException("Interrupted while closing JSON storage", interrupted);
                }
            }
        }
    }

    @Override
    public String diagnosticName() {
        return "storage";
    }

    @Override
    public Map<String, ?> diagnosticSnapshot() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("backend", "json");
        result.put("file", String.valueOf(file.getFileName()));
        result.put("sessions", sessions.size());
        result.put("loaded", state != null);
        synchronized (lock) {
            result.put("closed", disposed);
            result.put("worker_active", executor != null);
        }
        return result;
    }

    private ExecutorService executor() {
        synchronized (lock) {
            if (executor == null) {
                executor = Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "klib-json-storage");
                    thread.setDaemon(true);
                    executorThread = thread;
                    return thread;
                });
            }
            return executor;
        }
    }

    /** 打开失败且没有活动会话时停止执行器线程。 */
    private void shutdownIfUnused() {
        synchronized (lock) {
            if (sessions.isEmpty() && executor != null) {
                executor.shutdown();
                executor = null;
            }
        }
    }

    private <T> CompletionStage<T> executeTransaction(StorageTransaction<T> transaction) {
        ExecutorService active;
        synchronized (lock) {
            if (disposed) {
                return failed(new IllegalStateException("provider is disposed"));
            }
            active = executor();
        }
        return CompletableFuture.supplyAsync(() -> {
            State candidate = (state == null ? new State() : state).copy();
            try {
                T result = transaction.execute(new JsonTransactionContext(candidate));
                validateState(candidate);
                write(file, candidate);
                state = candidate;
                return result;
            } catch (Exception error) {
                throw error instanceof StorageException
                        ? (StorageException) error
                        : new StorageException("JSON storage transaction failed", error);
            }
        }, active);
    }

    private <T> CompletionStage<T> executeReadOnly(StorageTransaction<T> operation) {
        ExecutorService active;
        synchronized (lock) {
            if (disposed) {
                return failed(new IllegalStateException("provider is disposed"));
            }
            active = executor();
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                State current = state == null ? new State() : state;
                return operation.execute(new JsonTransactionContext(current));
            } catch (Exception error) {
                throw error instanceof StorageException
                        ? (StorageException) error
                        : new StorageException("JSON storage read failed", error);
            }
        }, active);
    }

    private static State read(Path file) {
        if (!Files.exists(file)) {
            return new State();
        }
        try {
            long fileBytes = Files.size(file);
            if (fileBytes > MAX_FILE_BYTES) {
                throw new StorageException("JSON storage exceeds " + MAX_FILE_BYTES
                        + " byte limit: " + file);
            }
            checkJsonDepth(file);
            try (Reader reader = boundedReader(file)) {
                State loaded = GSON.fromJson(reader, State.class);
                State normalized = loaded == null ? new State() : loaded.normalized();
                validateState(normalized);
                return normalized;
            }
        } catch (IOException | RuntimeException error) {
            if (error instanceof StorageException) {
                throw (StorageException) error;
            }
            throw new StorageException("Could not read JSON storage " + file, error);
        }
    }

    private static void write(Path file, State value) {
        Path parent = file.getParent();
        Path temporary = null;
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temporaryDirectory = parent == null
                    ? file.toAbsolutePath().getParent()
                    : parent;
            if (temporaryDirectory == null) {
                throw new IOException("JSON storage path has no parent directory: " + file);
            }
            temporary = Files.createTempFile(
                    temporaryDirectory,
                    "." + file.getFileName() + "-",
                    ".tmp");
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS)) {
                Writer writer = new BufferedWriter(
                        new OutputStreamWriter(new LimitedOutputStream(
                                Channels.newOutputStream(channel), MAX_FILE_BYTES), StandardCharsets.UTF_8));
                GSON.toJson(value, writer);
                writer.flush();
            }
            try {
                Files.move(temporary, file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException error) {
            if (error instanceof StorageException) {
                throw (StorageException) error;
            }
            throw new StorageException("Could not persist JSON storage " + file, error);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 写入已成功，或其主要失败原因更值得保留。
                }
            }
        }
    }

    private static <T> CompletionStage<T> failed(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<T>();
        future.completeExceptionally(error);
        return future;
    }

    /** 提供器共享状态的视图；释放操作只会分离该视图。 */
    private final class JsonStorageSession implements StorageSession {
        private final AtomicBoolean sessionDisposed = new AtomicBoolean();

        @Override
        public CompletionStage<Optional<byte[]>> get(String namespace, String key) {
            if (sessionDisposed.get()) {
                return failed(new IllegalStateException("session is disposed"));
            }
            return executeReadOnly(context -> context.get(namespace, key));
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
            if (sessionDisposed.get()) {
                return failed(new IllegalStateException("session is disposed"));
            }
            return executeReadOnly(context -> context.entries(namespace));
        }

        @Override
        public <T> CompletionStage<T> transaction(StorageTransaction<T> transaction) {
            if (transaction == null) {
                throw new NullPointerException("transaction");
            }
            if (sessionDisposed.get()) {
                return failed(new IllegalStateException("session is disposed"));
            }
            return executeTransaction(transaction);
        }

        @Override
        public void dispose() {
            if (sessionDisposed.compareAndSet(false, true)) {
                sessions.remove(this);
            }
        }
    }

    private static final class JsonTransactionContext implements TransactionContext {
        private final State state;

        private JsonTransactionContext(State state) {
            this.state = state;
        }

        @Override
        public Optional<byte[]> get(String namespace, String key) {
            Map<String, String> entries = state.values.get(requireName(namespace, "namespace"));
            String value = entries == null ? null : entries.get(requireName(key, "key"));
            return value == null ? Optional.empty() : Optional.of(Base64.getDecoder().decode(value));
        }

        @Override
        public void put(String namespace, String key, byte[] value) {
            requireName(namespace, "namespace", MAX_NAMESPACE_BYTES);
            requireName(key, "key", MAX_KEY_BYTES);
            if (value == null) {
                throw new NullPointerException("value");
            }
            if (value.length > MAX_VALUE_BYTES) {
                throw new IllegalArgumentException("value exceeds " + MAX_VALUE_BYTES
                        + " byte limit");
            }
            state.values.computeIfAbsent(namespace, ignored -> new LinkedHashMap<String, String>())
                    .put(key, Base64.getEncoder().encodeToString(copy(value)));
        }

        @Override
        public void delete(String namespace, String key) {
            Map<String, String> entries = state.values.get(requireName(namespace, "namespace"));
            if (entries != null) {
                entries.remove(requireName(key, "key"));
                if (entries.isEmpty()) {
                    state.values.remove(namespace);
                }
            }
        }

        @Override
        public Map<String, byte[]> entries(String namespace) {
            Map<String, String> stored = state.values.get(requireName(namespace, "namespace"));
            if (stored == null) {
                return Collections.emptyMap();
            }
            Map<String, byte[]> result = new LinkedHashMap<String, byte[]>();
            for (Map.Entry<String, String> entry : stored.entrySet()) {
                result.put(entry.getKey(), Base64.getDecoder().decode(entry.getValue()));
            }
            return result;
        }

        @Override
        public int schemaVersion(String schemaName) {
            Integer version = state.schemas.get(requireName(
                    schemaName, "schemaName", MAX_NAMESPACE_BYTES));
            return version == null ? 0 : version;
        }

        @Override
        public void schemaVersion(String schemaName, int version) {
            state.schemas.put(requireName(
                    schemaName, "schemaName", MAX_NAMESPACE_BYTES), version);
        }
    }

    private static final class State {
        private Map<String, Integer> schemas = new LinkedHashMap<String, Integer>();
        private Map<String, Map<String, String>> values = new LinkedHashMap<String, Map<String, String>>();

        private State normalized() {
            if (schemas == null) {
                schemas = new LinkedHashMap<String, Integer>();
            }
            if (values == null) {
                values = new LinkedHashMap<String, Map<String, String>>();
            }
            return this;
        }

        private State copy() {
            State copy = new State();
            copy.schemas.putAll(schemas);
            for (Map.Entry<String, Map<String, String>> entry : values.entrySet()) {
                copy.values.put(entry.getKey(), new LinkedHashMap<String, String>(entry.getValue()));
            }
            return copy;
        }
    }

    private static String requireName(String value, String label) {
        int maxBytes = "key".equals(label) ? MAX_KEY_BYTES : MAX_NAMESPACE_BYTES;
        return requireName(value, label, maxBytes);
    }

    private static String requireName(String value, String label, int maxBytes) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(label + " exceeds " + maxBytes + " UTF-8 byte limit");
        }
        return value;
    }

    private static void validateState(State state) {
        if (state.schemas.size() > MAX_SCHEMAS) {
            throw new StorageException("JSON storage exceeds " + MAX_SCHEMAS + " schema limit");
        }
        for (String schemaName : state.schemas.keySet()) {
            requireName(schemaName, "schemaName", MAX_NAMESPACE_BYTES);
            if (state.schemas.get(schemaName) == null) {
                throw new StorageException("JSON storage contains a null schema version");
            }
        }
        if (state.values.size() > MAX_NAMESPACES) {
            throw new StorageException("JSON storage exceeds " + MAX_NAMESPACES
                    + " namespace limit");
        }
        int entries = 0;
        long valueBytes = 0L;
        for (Map.Entry<String, Map<String, String>> namespace : state.values.entrySet()) {
            requireName(namespace.getKey(), "namespace", MAX_NAMESPACE_BYTES);
            if (namespace.getValue() == null) {
                throw new StorageException("JSON storage contains a null namespace");
            }
            entries += namespace.getValue().size();
            if (entries > MAX_ENTRIES) {
                throw new StorageException("JSON storage exceeds " + MAX_ENTRIES + " entry limit");
            }
            for (Map.Entry<String, String> entry : namespace.getValue().entrySet()) {
                requireName(entry.getKey(), "key", MAX_KEY_BYTES);
                String encoded = entry.getValue();
                if (encoded == null || encoded.length() > encodedLength(MAX_VALUE_BYTES)) {
                    throw new StorageException("JSON storage value exceeds " + MAX_VALUE_BYTES
                            + " byte limit");
                }
                final byte[] decoded;
                try {
                    decoded = Base64.getDecoder().decode(encoded);
                } catch (IllegalArgumentException malformed) {
                    throw new StorageException("JSON storage contains invalid Base64 value", malformed);
                }
                if (decoded.length > MAX_VALUE_BYTES) {
                    throw new StorageException("JSON storage value exceeds " + MAX_VALUE_BYTES
                            + " byte limit");
                }
                valueBytes += decoded.length;
                if (valueBytes > MAX_TOTAL_VALUE_BYTES) {
                    throw new StorageException("JSON storage exceeds " + MAX_TOTAL_VALUE_BYTES
                            + " total value byte limit");
                }
            }
        }
    }

    private static int encodedLength(int bytes) {
        return ((bytes + 2) / 3) * 4;
    }

    private static void checkJsonDepth(Path file) throws IOException {
        try (JsonReader reader = new JsonReader(boundedReader(file))) {
            reader.setLenient(false);
            consumeJsonValue(reader, 1);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("JSON storage contains trailing data");
            }
        }
    }

    private static void consumeJsonValue(JsonReader reader, int depth) throws IOException {
        if (depth > MAX_JSON_DEPTH) {
            throw new IOException("JSON storage exceeds nesting depth " + MAX_JSON_DEPTH);
        }
        JsonToken token = reader.peek();
        if (token == JsonToken.BEGIN_OBJECT) {
            reader.beginObject();
            while (reader.hasNext()) {
                reader.nextName();
                consumeJsonValue(reader, depth + 1);
            }
            reader.endObject();
        } else if (token == JsonToken.BEGIN_ARRAY) {
            reader.beginArray();
            while (reader.hasNext()) {
                consumeJsonValue(reader, depth + 1);
            }
            reader.endArray();
        } else {
            reader.skipValue();
        }
    }

    private static Reader boundedReader(Path file) throws IOException {
        return new InputStreamReader(new LimitedInputStream(
                Files.newInputStream(file), MAX_FILE_BYTES), StandardCharsets.UTF_8);
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long count;

        private LimitedInputStream(InputStream input, long limit) {
            super(input);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1 && ++count > limit) {
                throw new IOException("JSON storage exceeds " + limit + " byte limit");
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0 && (count += read) > limit) {
                throw new IOException("JSON storage exceeds " + limit + " byte limit");
            }
            return read;
        }
    }

    private static final class LimitedOutputStream extends FilterOutputStream {
        private final long limit;
        private long count;

        private LimitedOutputStream(OutputStream output, long limit) {
            super(output);
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            out.write(value);
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            ensureCapacity(length);
            out.write(bytes, offset, length);
            count += length;
        }

        private void ensureCapacity(int additionalBytes) throws IOException {
            if (additionalBytes > limit - count) {
                throw new IOException("JSON storage exceeds " + limit + " byte limit");
            }
        }
    }

    private static byte[] copy(byte[] value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        return Arrays.copyOf(value, value.length);
    }
}
