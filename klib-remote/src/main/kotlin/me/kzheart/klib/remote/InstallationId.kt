package me.kzheart.klib.remote

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.util.Locale
import java.util.function.Supplier
import java.util.logging.Level
import java.util.logging.Logger

/** 仅使用安全随机数生成、按商品持久化的匿名安装标识。 */
class InstallationId private constructor(private val cacheFile: Path) : Supplier<String> {
    @Volatile private var cached: String? = null
    override fun get(): String {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: (read() ?: randomValue().also { write(it) }).also { cached = it }
        }
    }
    private fun read(): String? {
        if (!Files.isRegularFile(cacheFile, NOFOLLOW_LINKS)) return null
        return try { String(Files.readAllBytes(cacheFile), UTF_8).trim { it <= ' ' }.takeIf { valid(it) } }
            catch (failure: IOException) { LOGGER.log(Level.FINE, "Unable to read Remote installation id", failure); null }
    }
    private fun write(value: String) {
        try {
            Files.createDirectories(cacheFile.parent)
            val temporary = Files.createTempFile(cacheFile.parent, "installation-", ".tmp")
            try {
                Files.write(temporary, value.toByteArray(UTF_8))
                Files.move(temporary, cacheFile, REPLACE_EXISTING, ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, cacheFile, REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        } catch (failure: IOException) { LOGGER.log(Level.WARNING, "Unable to persist Remote installation id", failure) }
    }
    companion object {
        private val LOGGER = Logger.getLogger(InstallationId::class.java.name)
        private val RANDOM = SecureRandom()
        const val DIRECTORY_NAME = ".klib-remote"
        @JvmStatic fun forProduct(productId: String, dataDirectory: Path): InstallationId {
            val product = Texts.requireText(productId, "productId")
            require(product.toByteArray(UTF_8).size <= 512) { "productId is too long" }
            return InstallationId(dataDirectory.resolve(DIRECTORY_NAME).resolve("installation-${productFileKey(product)}"))
        }
        @JvmStatic fun productFileKey(product: String): String = try {
            hex(MessageDigest.getInstance("SHA-256").digest(product.toByteArray(UTF_8)).copyOf(16))
        } catch (impossible: NoSuchAlgorithmException) { throw IllegalStateException("SHA-256 is unavailable", impossible) }
        private fun randomValue(): String = "inst_" + hex(ByteArray(16).also { RANDOM.nextBytes(it) })
        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
        private fun valid(value: String): Boolean = value.startsWith("inst_") && value.length == 37 &&
            value.substring(5).all { it in '0'..'9' || it in 'a'..'f' }
    }
}
