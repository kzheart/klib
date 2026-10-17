package me.kzheart.klib.remote

import java.util.Collections

/** batch 中每条事件的终结接收结果。 */
class RemoteBatchResult internal constructor(results: List<EventResult>, private val accepted: Int,
    private val duplicate: Int, private val rejected: Int) {
    private val results = Collections.unmodifiableList(results)
    fun results(): List<EventResult> = results
    fun accepted(): Int = accepted
    fun duplicate(): Int = duplicate
    fun rejected(): Int = rejected
    class EventResult internal constructor(private val index: Int, private val eventId: String,
        private val status: Status, private val error: String?) {
        fun index(): Int = index
        fun eventId(): String = eventId
        fun status(): Status = status
        fun error(): String? = error
    }
    enum class Status {
        ACCEPTED, DUPLICATE, REJECTED;
        companion object {
            @JvmStatic fun fromWire(value: String): Status = when (value) {
                "accepted" -> ACCEPTED
                "duplicate" -> DUPLICATE
                "rejected" -> REJECTED
                else -> throw IllegalArgumentException("unsupported result status")
            }
        }
    }
}
