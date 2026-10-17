package me.kzheart.klib.remote

/** /ingest/v1/settings 的策略、服务端预算与保留期。 */
class RemoteSettings internal constructor(private val keyStatus: String, private val environment: String,
    private val acceptingEvents: Boolean, private val policy: RemotePolicy,
    private val limits: Limits, private val retention: Retention) {
    fun keyStatus(): String = keyStatus
    fun environment(): String = environment
    fun acceptingEvents(): Boolean = acceptingEvents
    fun policy(): RemotePolicy = policy
    fun limits(): Limits = limits
    fun retention(): Retention = retention

    class Limits internal constructor(private val compressed: Long, private val decompressed: Long,
        private val batch: Int, private val event: Int, private val message: Int, private val attributes: Int,
        private val installationId: Int, private val eventId: Int, private val attributeKey: Int,
        private val attributeValue: Int, private val environmentField: Int, private val keyEvents: Int,
        private val ipEvents: Int, private val installationEvents: Int, private val asnEvents: Int,
        private val productSpikeEvents: Int) {
        init {
            require(compressed > 0 && decompressed > 0 &&
                listOf(batch, event, message, attributes, installationId, eventId, attributeKey, attributeValue,
                    environmentField, keyEvents, ipEvents, installationEvents, asnEvents, productSpikeEvents).all { it > 0 } &&
                event <= decompressed && listOf(message, installationId, eventId, attributeKey, attributeValue, environmentField).all { it <= event } &&
                listOf(keyEvents, ipEvents, installationEvents, asnEvents, productSpikeEvents).all { batch <= it }) { "invalid Remote limits" }
        }
        fun maxCompressedBytes(): Long = compressed
        fun maxDecompressedBytes(): Long = decompressed
        fun maxBatchEvents(): Int = batch
        fun maxEventBytes(): Int = event
        fun maxMessageBytes(): Int = message
        fun maxAttributes(): Int = attributes
        fun maxInstallationIdBytes(): Int = installationId
        fun maxEventIdBytes(): Int = eventId
        fun maxAttributeKeyBytes(): Int = attributeKey
        fun maxAttributeValueBytes(): Int = attributeValue
        fun maxEnvironmentFieldBytes(): Int = environmentField
        fun keyEventsPerMinute(): Int = keyEvents
        fun ipEventsPerMinute(): Int = ipEvents
        fun installationEventsPerMinute(): Int = installationEvents
        fun asnEventsPerMinute(): Int = asnEvents
        fun productSpikeEventsPerMinute(): Int = productSpikeEvents
    }
    class Retention internal constructor(private val logs: Int, private val incidents: Int, private val aggregates: Int) {
        init { require(logs >= 1 && incidents >= logs && aggregates >= incidents) { "invalid Remote retention" } }
        fun logsDays(): Int = logs
        fun incidentsDays(): Int = incidents
        fun aggregatesDays(): Int = aggregates
    }
}
