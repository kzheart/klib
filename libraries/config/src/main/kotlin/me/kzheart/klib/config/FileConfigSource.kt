package me.kzheart.klib.config

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.logging.Logger
import me.kzheart.klib.scope.Disposable

/** File source with prepared migrations, optimistic conflict checks, and atomic persistence. */
class FileConfigSource(
    file: Path,
    private val defaultContent: String,
    private val defaultSchemaVersion: Int,
    private val migrations: MigrationRunner,
    private val watching: Boolean,
) : ConfigSource {
    private val ioLock = Any()
    private var defaultsTemplate: YamlDocument? = null
    private val file = file.toAbsolutePath().normalize()
    init { require(defaultSchemaVersion in 0..migrations.latestVersion()) { "defaultSchemaVersion must be between 0 and ${migrations.latestVersion()}" } }
    constructor(file: Path, defaultContent: String, migrations: MigrationRunner) : this(file, defaultContent, migrations.latestVersion(), migrations, true)
    constructor(file: Path, defaultContent: String, migrations: MigrationRunner, watching: Boolean) : this(file, defaultContent, migrations.latestVersion(), migrations, watching)
    constructor(file: Path, defaultContent: String, defaultSchemaVersion: Int, migrations: MigrationRunner) : this(file, defaultContent, defaultSchemaVersion, migrations, true)
    override fun sourceName(): String = file.toString()
    override fun prepare(): PreparedConfig = synchronized(ioLock) {
        val existed = Files.exists(file)
        val originalContent = if (existed) readStrictUtf8(file) else defaultContent
        val document: YamlDocument
        val writeRequired: Boolean
        if (existed) {
            document = YamlDocument.parse(sourceName(), originalContent)
            val oldVersion = document.schemaVersion()
            val newVersion = migrations.migrate(document, oldVersion)
            val merged = document.mergeDefaultsChanged(parseShippedDefaults("${sourceName()} (defaults)"))
            writeRequired = oldVersion != newVersion || merged
        } else {
            document = parseShippedDefaults(sourceName())
            writeRequired = true
        }
        val persistedContent = if (writeRequired) document.toYaml() else originalContent
        object : PreparedConfig {
            private var committed = false
            override fun document(): YamlDocument = document
            override fun revision(): String = persistedContent
            override fun commit() = synchronized(ioLock) {
                if (!committed) {
                    verifyUnchanged(existed, originalContent)
                    if (writeRequired) writeAtomically(persistedContent)
                    committed = true
                }
            }
        }
    }
    override fun watch(listener: Runnable): Disposable = if (!watching) Disposable {} else
        FileWatchHandle.start(parentDirectory(), { it == file }, listener)
    fun path(): Path = file

    private fun parseShippedDefaults(source: String): YamlDocument = synchronized(ioLock) {
        val template = defaultsTemplate ?: YamlDocument.parse(sourceName(), defaultContent).also { defaults ->
            val declaredVersion = defaults.schemaVersion()
            if (defaults.node("_schema-version").exists() && declaredVersion != defaultSchemaVersion) throw ConfigException(
                "${defaults.sourceName()}:_schema-version: expected shipped default version $defaultSchemaVersion but found $declaredVersion")
            if (!defaults.node("_schema-version").exists() && defaultSchemaVersion > 0) defaults.setInteger("_schema-version", defaultSchemaVersion)
            migrations.migrate(defaults, defaultSchemaVersion)
            defaultsTemplate = defaults
        }
        template.copy(source)
    }
    private fun verifyUnchanged(existed: Boolean, originalContent: String) {
        val existsNow = Files.exists(file)
        if (existsNow != existed || existsNow && readStrictUtf8(file) != originalContent) {
            throw ConfigException("$file:<root>: source changed while configuration was loading")
        }
    }
    private fun writeAtomically(content: String) {
        try {
            val parent = parentDirectory()
            Files.createDirectories(parent)
            val temporary = Files.createTempFile(parent, file.fileName.toString(), ".tmp")
            try {
                FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                    val buffer = ByteBuffer.wrap(content.toByteArray(StandardCharsets.UTF_8))
                    while (buffer.hasRemaining()) channel.write(buffer)
                }
                try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (unsupported: AtomicMoveNotSupportedException) { replaceNonAtomically(temporary) }
            } finally { Files.deleteIfExists(temporary) }
        } catch (failure: IOException) { throw ConfigException("$file: cannot write configuration", failure) }
    }
    private fun replaceNonAtomically(temporary: Path) {
        if (Files.exists(file)) {
            val backup = file.resolveSibling("${file.fileName}.bak")
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING)
            logger.warning("$file: atomic replace is not supported here; kept a backup at $backup before overwriting")
        }
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
    }
    private fun parentDirectory(): Path = file.parent ?: throw ConfigException("$file: configuration path has no parent directory")

    companion object {
        private val logger = Logger.getLogger(FileConfigSource::class.java.name)
        @JvmStatic fun fromClasspath(file: Path, loader: ClassLoader, resourcePath: String, migrations: MigrationRunner): FileConfigSource = try {
            loader.getResourceAsStream(resourcePath).use { input ->
                if (input == null) throw ConfigException("Classpath config resource was not found: $resourcePath")
                FileConfigSource(file, String(input.readBytes(), StandardCharsets.UTF_8), migrations)
            }
        } catch (failure: IOException) { throw ConfigException("Cannot read classpath config resource: $resourcePath", failure) }
        @JvmStatic fun readStrictUtf8(file: Path): String = try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString()
        } catch (failure: CharacterCodingException) {
            throw ConfigException("$file: file is not valid UTF-8; re-save it with UTF-8 encoding", failure)
        } catch (failure: IOException) { throw ConfigException("$file: cannot read configuration", failure) }
    }
}
