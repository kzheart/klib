package me.kzheart.klib.remote

import java.util.Collections

/** Kotlin owns the receipt snapshot; the Java boundary preserves nullable error access. */
internal class RemoteBatchValues(results: List<RemoteBatchResult.EventResult>, val accepted: Int,
    val duplicate: Int, val rejected: Int) {
    val results: List<RemoteBatchResult.EventResult> = Collections.unmodifiableList(results)
}
internal class RemoteEventReceipt(val index: Int, val eventId: String,
    val status: RemoteBatchResult.Status, val error: String?)
internal object RemoteBatchStatus {
    @JvmStatic fun parse(value: String): RemoteBatchResult.Status = when (value) {
        "accepted" -> RemoteBatchResult.Status.ACCEPTED
        "duplicate" -> RemoteBatchResult.Status.DUPLICATE
        "rejected" -> RemoteBatchResult.Status.REJECTED
        else -> throw IllegalArgumentException("unsupported result status")
    }
}
