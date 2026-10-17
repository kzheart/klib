package me.kzheart.klib.remote

import java.nio.charset.StandardCharsets.UTF_8

class RemoteBatchEnvelope(installationId: ByteArray, environment: ByteArray) {
    private val installationId = installationId.copyOf()
    private val environment = environment.copyOf()
    fun installationId(): ByteArray = installationId.copyOf()
    fun environment(): ByteArray = environment.copyOf()
    fun installationIdText(): String = String(installationId, UTF_8)
    fun sameAs(other: RemoteBatchEnvelope): Boolean =
        installationId.contentEquals(other.installationId) && environment.contentEquals(other.environment)
}
