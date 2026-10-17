package me.kzheart.klib.remote

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.nio.charset.StandardCharsets.UTF_8
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.zip.GZIPOutputStream

/** Java 8 HTTP 边界，禁止重定向且限制响应体大小。 */
class HttpRpkTransport @JvmOverloads constructor(endpoint: String?, private val publicKey: String?,
    insecureLoopback: Boolean, private val connectTimeoutMillis: Int = 5000,
    private val readTimeoutMillis: Int = 10000) : RpkTransport {
    private val endpoint = parse(endpoint, insecureLoopback)
    init {
        require(publicKey != null && PUBLIC_KEY.matches(publicKey)) {
            "publicKey must be rpk_live_ or rpk_test_ followed by 43 URL-safe characters"
        }
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0) { "HTTP timeouts must be positive" }
    }
    @Throws(IOException::class)
    override fun settings(): String = String(request("GET", "/ingest/v1/settings", null, 200), UTF_8)
    @Throws(IOException::class)
    override fun sendBatch(json: ByteArray): String {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(json) }
        return String(request("POST", "/ingest/v1/batches", output.toByteArray(), 202), UTF_8)
    }
    override fun queueIdentity(): String = endpoint.toExternalForm() + '\u0000' + publicKey

    private fun request(method: String, path: String, body: ByteArray?, expected: Int): ByteArray {
        val connection = URL(endpoint.toString() + path).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $publicKey")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("Content-Encoding", "gzip")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val response = readLimited(if (status >= 400) connection.errorStream else connection.inputStream)
            if (status != expected) throw RemoteHttpException(status, retryAfterMillis(connection.getHeaderField("Retry-After")))
            return response
        } finally { connection.disconnect() }
    }
    private fun readLimited(input: InputStream?): ByteArray {
        if (input == null) return ByteArray(0)
        return input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            var total = 0
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_RESPONSE_BYTES) throw IOException("Remote response exceeds limit")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
    companion object {
        private const val MAX_RESPONSE_BYTES = 64 * 1024
        private val PUBLIC_KEY = Regex("^rpk_(?:live|test)_[A-Za-z0-9_-]{43}$")
        private fun retryAfterMillis(value: String?): Long {
            val normalized = value?.trim { it <= ' ' } ?: return -1L
            return try {
                val seconds = normalized.toLong()
                when { seconds < 0 -> -1L; seconds > Long.MAX_VALUE / 1000L -> Long.MAX_VALUE; else -> seconds * 1000L }
            } catch (_: NumberFormatException) {
                try { maxOf(0L, ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli() - System.currentTimeMillis()) }
                catch (_: DateTimeParseException) { -1L }
            }
        }
        private fun parse(value: String?, insecureLoopback: Boolean): URL {
            require(value != null) { "endpoint must not be null" }
            val normalized = value.removeSuffix("/")
            try {
                val url = URL(normalized)
                val secure = url.protocol == "https"
                val loopback = insecureLoopback && url.protocol == "http" &&
                    (url.host == "127.0.0.1" || url.host.equals("localhost", ignoreCase = true) || url.host == "::1")
                require(secure || loopback) { "endpoint must use https" }
                require(url.userInfo == null && url.query == null && url.ref == null) { "endpoint must not contain user info, query, or fragment" }
                return url
            } catch (failure: MalformedURLException) { throw IllegalArgumentException("invalid endpoint", failure) }
        }
    }
}
