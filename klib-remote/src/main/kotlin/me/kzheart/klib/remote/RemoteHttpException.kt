package me.kzheart.klib.remote

import java.io.IOException

/** HTTP 非预期状态及 Retry-After。 */
class RemoteHttpException internal constructor(private val status: Int, private val retryAfterMillis: Long) :
    IOException("Remote returned HTTP $status") {
    internal constructor(status: Int) : this(status, -1L)
    fun status(): Int = status
    fun retryAfterMillis(): Long = retryAfterMillis
    companion object { private const val serialVersionUID = 1L }
}
