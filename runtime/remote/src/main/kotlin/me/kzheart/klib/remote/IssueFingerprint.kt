package me.kzheart.klib.remote

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Locale

/** 类型、cause 和忽略行号的堆栈共同生成稳定指纹。 */
class IssueFingerprint private constructor() {
    companion object {
        @JvmStatic fun of(error: Throwable): String {
            val normalized = StringBuilder()
            var current: Throwable? = error
            var causes = 0
            while (current != null && causes < 32) {
                normalized.append(current.javaClass.name).append('\n')
                for (frame in current.stackTrace) normalized.append(frame.className).append('#')
                    .append(frame.methodName).append('(').append(frame.fileName).append(")\n")
                current = current.cause
                causes++
            }
            val digest = try { MessageDigest.getInstance("SHA-256").digest(normalized.toString().toByteArray(UTF_8)) }
                catch (impossible: NoSuchAlgorithmException) { throw IllegalStateException("SHA-256 is unavailable", impossible) }
            return (0 until 16).joinToString("") { String.format(Locale.ROOT, "%02x", digest[it].toInt() and 0xff) }
        }
    }
}
