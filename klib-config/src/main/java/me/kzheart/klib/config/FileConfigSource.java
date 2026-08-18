package me.kzheart.klib.config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.logging.Logger;
import me.kzheart.klib.scope.Disposable;

/** 支持默认配置提取、合并、迁移和原子写入的生产文件源。 */
public final class FileConfigSource implements ConfigSource {
    private static final Logger LOGGER = Logger.getLogger(FileConfigSource.class.getName());

    private final Object ioLock = new Object();
    /** 已解析的内置默认配置，仅迁移一次并通过深拷贝复用，由 ioLock 保护。 */
    private YamlDocument defaultsTemplate;
    private final Path file;
    private final String defaultContent;
    private final int defaultSchemaVersion;
    private final MigrationRunner migrations;
    private final boolean watching;

    public FileConfigSource(Path file, String defaultContent, MigrationRunner migrations) {
        this(file, defaultContent, migrations.latestVersion(), migrations, true);
    }

    public FileConfigSource(
            Path file,
            String defaultContent,
            MigrationRunner migrations,
            boolean watching
    ) {
        this(file, defaultContent, migrations.latestVersion(), migrations, watching);
    }

    public FileConfigSource(
            Path file,
            String defaultContent,
            int defaultSchemaVersion,
            MigrationRunner migrations
    ) {
        this(file, defaultContent, defaultSchemaVersion, migrations, true);
    }

    public FileConfigSource(
            Path file,
            String defaultContent,
            int defaultSchemaVersion,
            MigrationRunner migrations,
            boolean watching
    ) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.defaultContent = Objects.requireNonNull(defaultContent, "defaultContent");
        this.migrations = Objects.requireNonNull(migrations, "migrations");
        if (defaultSchemaVersion < 0 || defaultSchemaVersion > migrations.latestVersion()) {
            throw new IllegalArgumentException(
                    "defaultSchemaVersion must be between 0 and " + migrations.latestVersion());
        }
        this.defaultSchemaVersion = defaultSchemaVersion;
        this.watching = watching;
    }

    public static FileConfigSource fromClasspath(
            Path file,
            ClassLoader loader,
            String resourcePath,
            MigrationRunner migrations
    ) {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(resourcePath, "resourcePath");
        try (InputStream input = loader.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new ConfigException("Classpath config resource was not found: " + resourcePath);
            }
            return new FileConfigSource(file, readUtf8(input), migrations);
        } catch (IOException failure) {
            throw new ConfigException("Cannot read classpath config resource: " + resourcePath, failure);
        }
    }

    @Override
    public String sourceName() {
        return file.toString();
    }

    @Override
    public PreparedConfig prepare() {
        synchronized (ioLock) {
            final boolean existed = Files.exists(file);
            final String originalContent = existed ? readFile() : defaultContent;

            final YamlDocument document;
            final boolean changed;
            if (existed) {
                document = YamlDocument.parse(sourceName(), originalContent);
                int oldVersion = document.schemaVersion();
                int newVersion = migrations.migrate(document, oldVersion);
                boolean migratedOrMerged = oldVersion != newVersion;
                YamlDocument defaults = parseShippedDefaults(sourceName() + " (defaults)");
                migratedOrMerged |= document.mergeDefaultsChanged(defaults);
                changed = migratedOrMerged;
            } else {
                document = parseShippedDefaults(sourceName());
                changed = true;
            }
            final boolean writeRequired = !existed || changed;
            final String persistedContent = writeRequired ? document.toYaml() : originalContent;
            return new PreparedConfig() {
                private boolean committed;

                @Override
                public YamlDocument document() {
                    return document;
                }

                @Override
                public void commit() {
                    synchronized (ioLock) {
                        if (committed) {
                            return;
                        }
                        verifyUnchanged(existed, originalContent);
                        if (writeRequired) {
                            writeAtomically(persistedContent);
                        }
                        committed = true;
                    }
                }

                @Override
                public String revision() {
                    return persistedContent;
                }
            };
        }
    }

    @Override
    public Disposable watch(Runnable listener) {
        if (!watching) {
            return () -> { };
        }
        Path parent = parentDirectory();
        return FileWatchHandle.start(parent, candidate -> candidate.equals(file), listener);
    }

    public Path path() {
        return file;
    }

    private YamlDocument parseShippedDefaults(String source) {
        synchronized (ioLock) {
            if (defaultsTemplate == null) {
                YamlDocument defaults = YamlDocument.parse(sourceName(), defaultContent);
                int declaredVersion = defaults.schemaVersion();
                if (defaults.node("_schema-version").exists()
                        && declaredVersion != defaultSchemaVersion) {
                    throw new ConfigException(
                            defaults.sourceName() + ":_schema-version: expected shipped default version "
                                    + defaultSchemaVersion + " but found " + declaredVersion);
                }
                if (!defaults.node("_schema-version").exists() && defaultSchemaVersion > 0) {
                    defaults.setInteger("_schema-version", defaultSchemaVersion);
                }
                migrations.migrate(defaults, defaultSchemaVersion);
                defaultsTemplate = defaults;
            }
            return defaultsTemplate.copy(source);
        }
    }

    private String readFile() {
        return readStrictUtf8(file);
    }

    /** 读取文件时拒绝格式错误的 UTF-8，而不是静默替换字节。 */
    static String readStrictUtf8(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw new ConfigException(
                    file + ": file is not valid UTF-8; re-save it with UTF-8 encoding", failure);
        } catch (IOException failure) {
            throw new ConfigException(file + ": cannot read configuration", failure);
        }
    }

    private void verifyUnchanged(boolean existed, String originalContent) {
        boolean existsNow = Files.exists(file);
        if (existsNow != existed || existsNow && !readFile().equals(originalContent)) {
            throw new ConfigException(
                    file + ":<root>: source changed while configuration was loading");
        }
    }

    private void writeAtomically(String content) {
        Path parent = parentDirectory();
        try {
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, file.getFileName().toString(), ".tmp");
            try {
                try (FileChannel channel = FileChannel.open(
                        temporary,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    ByteBuffer buffer = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                }
                try {
                    Files.move(
                            temporary,
                            file,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    replaceNonAtomically(temporary);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            throw new ConfigException(file + ": cannot write configuration", failure);
        }
    }

    /** 不支持原子移动的文件系统所用的兜底方案：先备份原文件。 */
    private void replaceNonAtomically(Path temporary) throws IOException {
        if (Files.exists(file)) {
            Path backup = file.resolveSibling(file.getFileName() + ".bak");
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.warning(file + ": atomic replace is not supported here; kept a backup at "
                    + backup + " before overwriting");
        }
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path parentDirectory() {
        Path parent = file.getParent();
        if (parent == null) {
            throw new ConfigException(file + ": configuration path has no parent directory");
        }
        return parent;
    }

    private static String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
