package me.kzheart.klib.remote

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.function.Supplier
import java.util.logging.Level
import java.util.zip.GZIPOutputStream

/** 公开 rpk_* Key 使用的 /ingest/v1 客户端。 */
internal class RemoteClientState(
    private val transport: RpkTransport,
    private val capabilities: RemoteCapabilities,
    private val installationId: Supplier<String>,
    private val environment: RemoteEnvironment
) {
    @Volatile private var effectivePolicy = RemotePolicy.builder().paused(true).build()
    @Volatile private var settings: RemoteSettings? = null

    fun policy(): RemotePolicy = effectivePolicy
    fun settings(): RemoteSettings? = settings

    @Throws(IOException::class)
    fun refreshPolicy(): RemotePolicy {
        val refreshed = RemoteWire.settings(transport.settings())
        var effective = refreshed.policy().restrictTo(capabilities)
        if (!refreshed.acceptingEvents()) {
            effective = RemotePolicy.builder().paused(true).minimumLevel(effective.minimumLevel())
                .sampleRate(effective.sampleRate()).build()
        }
        settings = RemoteSettings(refreshed.keyStatus(), refreshed.environment(), refreshed.acceptingEvents(),
            effective, refreshed.limits(), refreshed.retention())
        effectivePolicy = effective
        return effective
    }

    /** 同步网络发送；业务线程应使用 RemoteDelivery。 */
    @Throws(IOException::class)
    fun sendBatch(events: List<RemoteEvent>): RemoteBatchResult {
        require(events.isNotEmpty()) { "events must not be empty" }
        val batch = linkedMapOf<String, Any>(
            "schema_version" to 1,
            "installation_id" to Texts.requireText(installationId.get(), "installationId"),
            "environment" to environment.toMap(),
            "events" to events.map { it.toMap() })
        val response = transport.sendBatch(DiagnosticJson.write(batch).toByteArray(UTF_8))
        return RemoteWire.receipt(response, events.map { Texts.requireText(it.toMap()["event_id"]?.toString(), "event_id") })
    }

    fun snapshotEnvelope(): RemoteBatchEnvelope = RemoteBatchEnvelope(
        Texts.requireText(installationId.get(), "installationId").toByteArray(UTF_8),
        DiagnosticJson.write(environment.toMap()).toByteArray(UTF_8))

    fun queueIdentity(): ByteArray = try {
        MessageDigest.getInstance("SHA-256").digest(transport.queueIdentity().toByteArray(UTF_8))
    } catch (impossible: NoSuchAlgorithmException) {
        throw IllegalStateException("SHA-256 is unavailable", impossible)
    }

    @Throws(IOException::class)
    fun sendEncodedBatch(envelope: RemoteBatchEnvelope, events: List<ByteArray>, eventIds: List<String>): RemoteBatchResult {
        require(events.isNotEmpty()) { "events must not be empty" }
        return RemoteWire.receipt(transport.sendBatch(encodeBatch(envelope, events)), eventIds)
    }

    @Throws(IOException::class)
    fun batchFits(envelope: RemoteBatchEnvelope, events: List<ByteArray>, limits: RemoteSettings.Limits): Boolean {
        val batch = encodeBatch(envelope, events)
        if (batch.size > limits.maxDecompressedBytes()) return false
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(batch) }
        return output.size() <= limits.maxCompressedBytes()
    }

    private fun encodeBatch(envelope: RemoteBatchEnvelope, events: List<ByteArray>): ByteArray = buildString {
        append("{\"schema_version\":1,\"installation_id\":")
        JsonStrings.appendQuoted(this, envelope.installationIdText())
        append(",\"environment\":").append(String(envelope.environment(), UTF_8)).append(",\"events\":[")
        events.forEachIndexed { index, event ->
            if (index > 0) append(',')
            append(String(event, UTF_8))
        }
        append("]}")
    }.toByteArray(UTF_8)

    companion object {
        @JvmStatic fun http(endpoint: String, publicKey: String, capabilities: RemoteCapabilities,
            installationId: Supplier<String>, environment: RemoteEnvironment): RemoteClientState =
            RemoteClientState(HttpRpkTransport(endpoint, publicKey, false), capabilities, installationId, environment)

        @JvmStatic fun insecureLoopback(endpoint: String, publicKey: String, capabilities: RemoteCapabilities,
            installationId: Supplier<String>, environment: RemoteEnvironment): RemoteClientState =
            RemoteClientState(HttpRpkTransport(endpoint, publicKey, true), capabilities, installationId, environment)
    }
}

/** 协议边界集中进行严格结构检查，网络与重试层仅接收已验证的模型。 */
private object RemoteWire {
    fun settings(json: String): RemoteSettings = protocol("settings") {
        val root = obj(StrictJsonParser.parse(json))
        keys(root, "schema_version", "key_status", "environment", "accepting_events", "policy", "limits", "retention")
        require(integer(root["schema_version"]) == 1) { "unsupported schema" }
        val keyStatus = string(root, "key_status").also { require(it in setOf("active", "paused", "revoked")) { "invalid key status" } }
        val environment = string(root, "environment").also { require(it in setOf("production", "staging", "development")) { "invalid environment" } }
        val encodedPolicy = obj(root["policy"])
        keys(encodedPolicy, "paused", "exceptions", "logs", "manual_incidents", "minimum_level", "sample_rate")
        val policy = RemotePolicy.builder().paused(boolean(encodedPolicy, "paused"))
            .exceptions(boolean(encodedPolicy, "exceptions")).logs(boolean(encodedPolicy, "logs"))
            .manualIncidents(boolean(encodedPolicy, "manual_incidents"))
            .minimumLevel(level(string(encodedPolicy, "minimum_level")))
            .sampleRate(integer(encodedPolicy["sample_rate"])).build()
        val limits = obj(root["limits"])
        keys(limits, "max_compressed_bytes", "max_decompressed_bytes", "max_batch_events", "max_event_bytes",
            "max_message_bytes", "max_attributes", "max_installation_id_bytes", "max_event_id_bytes",
            "max_attribute_key_bytes", "max_attribute_value_bytes", "max_environment_field_bytes",
            "key_events_per_minute", "ip_events_per_minute", "installation_events_per_minute",
            "asn_events_per_minute", "product_spike_events_per_minute")
        val parsedLimits = RemoteSettings.Limits(long(limits, "max_compressed_bytes"), long(limits, "max_decompressed_bytes"),
            integer(limits["max_batch_events"]), integer(limits["max_event_bytes"]), integer(limits["max_message_bytes"]),
            integer(limits["max_attributes"]), integer(limits["max_installation_id_bytes"]), integer(limits["max_event_id_bytes"]),
            integer(limits["max_attribute_key_bytes"]), integer(limits["max_attribute_value_bytes"]), integer(limits["max_environment_field_bytes"]),
            integer(limits["key_events_per_minute"]), integer(limits["ip_events_per_minute"]), integer(limits["installation_events_per_minute"]),
            integer(limits["asn_events_per_minute"]), integer(limits["product_spike_events_per_minute"]))
        val retention = obj(root["retention"])
        keys(retention, "logs_days", "incidents_days", "aggregates_days")
        RemoteSettings(keyStatus, environment, boolean(root, "accepting_events"), policy, parsedLimits,
            RemoteSettings.Retention(integer(retention["logs_days"]), integer(retention["incidents_days"]), integer(retention["aggregates_days"])))
    }

    fun receipt(json: String, eventIds: List<String>): RemoteBatchResult = protocol("batch result") {
        val root = obj(StrictJsonParser.parse(json))
        keys(root, "results", "accepted", "duplicate", "rejected")
        val encodedResults = root["results"] as? List<*> ?: throw IllegalArgumentException("results")
        val results = encodedResults.map { encoded ->
            val item = obj(encoded)
            optionalKeys(item, setOf("index", "event_id", "status"), setOf("error"))
            val status = RemoteBatchResult.Status.fromWire(string(item, "status"))
            val error = if (item["error"] == null) null else string(item, "error")
            if (status == RemoteBatchResult.Status.REJECTED) require(error != null && error.trim { it <= ' ' }.isNotEmpty()) { "rejected result missing error" }
            else require(!item.containsKey("error")) { "successful result has error" }
            RemoteBatchResult.EventResult(integer(item["index"]), string(item, "event_id"), status, error)
        }
        val accepted = integer(root["accepted"])
        val duplicate = integer(root["duplicate"])
        val rejected = integer(root["rejected"])
        validateResults(results, eventIds, accepted, duplicate, rejected)
        RemoteBatchResult(results, accepted, duplicate, rejected)
    }

    private inline fun <T> protocol(description: String, parse: () -> T): T = try { parse() }
        catch (_: RuntimeException) { throw RemoteProtocolException("Remote returned invalid $description") }

    private fun validateResults(results: List<RemoteBatchResult.EventResult>, eventIds: List<String>,
        accepted: Int, duplicate: Int, rejected: Int) {
        require(results.size == eventIds.size && accepted >= 0 && duplicate >= 0 && rejected >= 0 &&
            accepted + duplicate + rejected == results.size) { "incomplete batch receipt" }
        val seen = BooleanArray(eventIds.size)
        var acceptedCount = 0
        var duplicateCount = 0
        var rejectedCount = 0
        for (result in results) {
            val index = result.index()
            require(index in seen.indices && !seen[index]) { "invalid result index" }
            seen[index] = true
            require(result.eventId() == eventIds[index]) { "result event_id mismatch" }
            when (result.status()) {
                RemoteBatchResult.Status.ACCEPTED -> acceptedCount++
                RemoteBatchResult.Status.DUPLICATE -> duplicateCount++
                RemoteBatchResult.Status.REJECTED -> rejectedCount++
            }
        }
        require(accepted == acceptedCount && duplicate == duplicateCount && rejected == rejectedCount) { "result counters mismatch" }
    }

    private fun keys(value: Map<*, *>, vararg names: String) {
        require(value.size == names.size) { "unexpected field" }
        require(names.all { value.containsKey(it) }) { "missing field" }
    }
    private fun optionalKeys(value: Map<*, *>, required: Set<String>, optional: Set<String>) {
        require(required.all { value.containsKey(it) }) { "missing field" }
        require(value.keys.all { it in required || it in optional }) { "unexpected field" }
    }
    private fun obj(value: Any?): Map<*, *> = value as? Map<*, *> ?: throw IllegalArgumentException("object field")
    private fun string(value: Map<*, *>, key: String): String = value[key] as? String ?: throw IllegalArgumentException("string field")
    private fun boolean(value: Map<*, *>, key: String): Boolean = value[key] as? Boolean ?: throw IllegalArgumentException("boolean field")
    private fun long(value: Map<*, *>, key: String): Long = value[key] as? Long ?: throw IllegalArgumentException("integer field")
    private fun integer(value: Any?): Int {
        val number = value as? Long ?: throw IllegalArgumentException("integer field")
        require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "integer field" }
        return number.toInt()
    }
    private fun level(value: String): Level = when (value) {
        "trace" -> Level.FINEST
        "debug" -> Level.FINE
        "info" -> Level.INFO
        "warn" -> Level.WARNING
        "error" -> Level.SEVERE
        else -> throw IllegalArgumentException("unsupported level")
    }
}
