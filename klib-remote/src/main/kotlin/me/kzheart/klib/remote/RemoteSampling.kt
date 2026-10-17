package me.kzheart.klib.remote

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

class RemoteSampling private constructor() {
    companion object {
        private val DOMAIN = "klib-remote-sample-v1\u0000".toByteArray(UTF_8)
        @JvmStatic fun accepts(eventId: String, sampleRate: Int): Boolean {
            if (sampleRate <= 0) return false
            if (sampleRate >= 100) return true
            val digest = try {
                MessageDigest.getInstance("SHA-256").apply { update(DOMAIN) }
                    .digest(Texts.requireText(eventId, "eventId").toByteArray(UTF_8))
            } catch (impossible: NoSuchAlgorithmException) {
                throw IllegalStateException("SHA-256 is unavailable", impossible)
            }
            val value = ((digest[0].toLong() and 0xffL) shl 24) or ((digest[1].toLong() and 0xffL) shl 16) or
                ((digest[2].toLong() and 0xffL) shl 8) or (digest[3].toLong() and 0xffL)
            return value % 100L < sampleRate
        }
    }
}
