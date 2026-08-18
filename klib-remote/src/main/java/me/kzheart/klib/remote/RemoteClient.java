package me.kzheart.klib.remote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.zip.GZIPOutputStream;

/** 公开 `rpk_*` Key 使用的 `/ingest/v1` 客户端。 */
public final class RemoteClient {
    private final RpkTransport transport;
    private final RemoteCapabilities capabilities;
    private final Supplier<String> installationId;
    private final RemoteEnvironment environment;
    private volatile RemotePolicy effectivePolicy;
    private volatile RemoteSettings settings;

    RemoteClient(RpkTransport transport, RemoteCapabilities capabilities,
            Supplier<String> installationId, RemoteEnvironment environment) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.installationId = Objects.requireNonNull(installationId, "installationId");
        this.environment = Objects.requireNonNull(environment, "environment");
        effectivePolicy = RemotePolicy.builder().paused(true).build();
    }

    /** 创建仅允许 HTTPS 的公开 Remote 客户端。 */
    public static RemoteClient http(String endpoint, String publicKey,
            RemoteCapabilities capabilities, Supplier<String> installationId,
            RemoteEnvironment environment) {
        return new RemoteClient(new HttpRpkTransport(endpoint, publicKey, false),
                capabilities, installationId, environment);
    }

    /** 创建允许 HTTP loopback 地址的本地测试客户端。 */
    public static RemoteClient insecureLoopback(String endpoint, String publicKey,
            RemoteCapabilities capabilities, Supplier<String> installationId,
            RemoteEnvironment environment) {
        return new RemoteClient(new HttpRpkTransport(endpoint, publicKey, true),
                capabilities, installationId, environment);
    }

    /** 返回当前有效策略；首次成功拉取前始终为 fail-closed。 */
    public RemotePolicy policy() { return effectivePolicy; }

    /** 返回最后一次成功拉取的设置；尚未成功拉取时返回 {@code null}。 */
    public RemoteSettings settings() { return settings; }

    /** 拉取远端策略并与构建能力取交集；失败时保留当前策略并向调用方抛出。 */
    public RemotePolicy refreshPolicy() throws IOException {
        RemoteSettings refreshed = parseSettings(transport.settings());
        RemotePolicy effective = refreshed.policy().restrictTo(capabilities);
        if (!refreshed.acceptingEvents()) {
            effective = RemotePolicy.builder()
                    .paused(true)
                    .minimumLevel(effective.minimumLevel())
                    .sampleRate(effective.sampleRate())
                    .build();
        }
        settings = new RemoteSettings(refreshed.keyStatus(), refreshed.environment(),
                refreshed.acceptingEvents(), effective, refreshed.limits(), refreshed.retention());
        effectivePolicy = effective;
        return effective;
    }

    /**
     * 同步发送一批事件。该方法执行网络 I/O；Minecraft 业务路径应改用
     * {@link RemoteDelivery}。
     */
    public RemoteBatchResult sendBatch(List<? extends RemoteEvent> events) throws IOException {
        Objects.requireNonNull(events, "events");
        if (events.isEmpty()) throw new IllegalArgumentException("events must not be empty");
        List<Map<String, Object>> encoded = new ArrayList<Map<String, Object>>(events.size());
        for (RemoteEvent event : events) encoded.add(Objects.requireNonNull(event, "event").toMap());
        Map<String, Object> batch = new LinkedHashMap<String, Object>();
        batch.put("schema_version", 1);
        batch.put("installation_id", Texts.requireText(installationId.get(), "installationId"));
        batch.put("environment", environment.toMap());
        batch.put("events", encoded);
        String response = transport.sendBatch(
                DiagnosticJson.write(batch).getBytes(StandardCharsets.UTF_8));
        return parseBatchResult(response, eventIds(events));
    }

    RemoteBatchEnvelope snapshotEnvelope() {
        return new RemoteBatchEnvelope(
                Texts.requireText(installationId.get(), "installationId")
                        .getBytes(StandardCharsets.UTF_8),
                DiagnosticJson.write(environment.toMap()).getBytes(StandardCharsets.UTF_8));
    }

    byte[] queueIdentity() {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    transport.queueIdentity().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    RemoteBatchResult sendEncodedBatch(RemoteBatchEnvelope envelope,
            List<byte[]> events, List<String> eventIds) throws IOException {
        Objects.requireNonNull(events, "events");
        if (events.isEmpty()) throw new IllegalArgumentException("events must not be empty");
        String response = transport.sendBatch(encodeBatch(envelope, events));
        return parseBatchResult(response, eventIds);
    }

    boolean batchFits(RemoteBatchEnvelope envelope, List<byte[]> events,
            RemoteSettings.Limits limits) throws IOException {
        byte[] batch = encodeBatch(envelope, events);
        if (batch.length > limits.maxDecompressedBytes()) return false;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(output);
        gzip.write(batch);
        gzip.close();
        return output.size() <= limits.maxCompressedBytes();
    }

    private static byte[] encodeBatch(RemoteBatchEnvelope envelope, List<byte[]> events) {
        StringBuilder encoded = new StringBuilder();
        encoded.append('{').append("\"schema_version\":1,\"installation_id\":");
        JsonStrings.appendQuoted(encoded, envelope.installationIdText());
        encoded.append(",\"environment\":")
                .append(new String(envelope.environment(), StandardCharsets.UTF_8))
                .append(",\"events\":[");
        for (int index = 0; index < events.size(); index++) {
            byte[] event = Objects.requireNonNull(events.get(index), "event");
            if (index > 0) encoded.append(',');
            encoded.append(new String(event, StandardCharsets.UTF_8));
        }
        encoded.append("]}");
        return encoded.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static RemoteSettings parseSettings(String json) throws IOException {
        try {
            Map<?, ?> root = requiredObject(StrictJsonParser.parse(json));
            requireKeys(root, "schema_version", "key_status", "environment",
                    "accepting_events", "policy", "limits", "retention");
            if (exactInt(root.get("schema_version")) != 1) {
                throw new IllegalArgumentException("unsupported schema");
            }
            String keyStatus = settingsKeyStatus(requiredString(root, "key_status"));
            String environment = settingsEnvironment(requiredString(root, "environment"));
            boolean acceptingEvents = requiredBoolean(root, "accepting_events");

            Map<?, ?> encodedPolicy = requiredObject(root.get("policy"));
            requireKeys(encodedPolicy, "paused", "exceptions", "logs", "manual_incidents",
                    "minimum_level", "sample_rate");
            RemotePolicy policy = RemotePolicy.builder()
                    .paused(requiredBoolean(encodedPolicy, "paused"))
                    .exceptions(requiredBoolean(encodedPolicy, "exceptions"))
                    .logs(requiredBoolean(encodedPolicy, "logs"))
                    .manualIncidents(requiredBoolean(encodedPolicy, "manual_incidents"))
                    .minimumLevel(parseLevel(requiredString(encodedPolicy, "minimum_level")))
                    .sampleRate(exactInt(encodedPolicy.get("sample_rate")))
                    .build();

            Map<?, ?> encodedLimits = requiredObject(root.get("limits"));
            requireKeys(encodedLimits, "max_compressed_bytes", "max_decompressed_bytes",
                    "max_batch_events", "max_event_bytes", "max_message_bytes",
                    "max_attributes", "max_installation_id_bytes", "max_event_id_bytes",
                    "max_attribute_key_bytes", "max_attribute_value_bytes",
                    "max_environment_field_bytes", "key_events_per_minute",
                    "ip_events_per_minute", "installation_events_per_minute",
                    "asn_events_per_minute", "product_spike_events_per_minute");
            RemoteSettings.Limits limits = new RemoteSettings.Limits(
                    requiredLong(encodedLimits, "max_compressed_bytes"),
                    requiredLong(encodedLimits, "max_decompressed_bytes"),
                    requiredInt(encodedLimits, "max_batch_events"),
                    requiredInt(encodedLimits, "max_event_bytes"),
                    requiredInt(encodedLimits, "max_message_bytes"),
                    requiredInt(encodedLimits, "max_attributes"),
                    requiredInt(encodedLimits, "max_installation_id_bytes"),
                    requiredInt(encodedLimits, "max_event_id_bytes"),
                    requiredInt(encodedLimits, "max_attribute_key_bytes"),
                    requiredInt(encodedLimits, "max_attribute_value_bytes"),
                    requiredInt(encodedLimits, "max_environment_field_bytes"),
                    requiredInt(encodedLimits, "key_events_per_minute"),
                    requiredInt(encodedLimits, "ip_events_per_minute"),
                    requiredInt(encodedLimits, "installation_events_per_minute"),
                    requiredInt(encodedLimits, "asn_events_per_minute"),
                    requiredInt(encodedLimits, "product_spike_events_per_minute"));

            Map<?, ?> encodedRetention = requiredObject(root.get("retention"));
            requireKeys(encodedRetention, "logs_days", "incidents_days", "aggregates_days");
            RemoteSettings.Retention retention = new RemoteSettings.Retention(
                    requiredInt(encodedRetention, "logs_days"),
                    requiredInt(encodedRetention, "incidents_days"),
                    requiredInt(encodedRetention, "aggregates_days"));
            return new RemoteSettings(keyStatus, environment, acceptingEvents,
                    policy, limits, retention);
        } catch (RuntimeException failure) {
            throw new RemoteProtocolException("Remote returned invalid settings");
        }
    }

    private static RemoteBatchResult parseBatchResult(String json, List<String> eventIds)
            throws IOException {
        try {
            Object decoded = StrictJsonParser.parse(json);
            if (!(decoded instanceof Map<?, ?>)) throw new IllegalArgumentException("receipt");
            Map<?, ?> receipt = (Map<?, ?>) decoded;
            requireKeys(receipt, "results", "accepted", "duplicate", "rejected");
            Object encodedResults = receipt.get("results");
            if (!(encodedResults instanceof List<?>)) throw new IllegalArgumentException("results");
            List<RemoteBatchResult.EventResult> results =
                    new ArrayList<RemoteBatchResult.EventResult>();
            for (Object encodedResult : (List<?>) encodedResults) {
                if (!(encodedResult instanceof Map<?, ?>)) {
                    throw new IllegalArgumentException("result");
                }
                Map<?, ?> item = (Map<?, ?>) encodedResult;
                requireOptionalKeys(item, new String[] {"index", "event_id", "status"},
                        new String[] {"error"});
                RemoteBatchResult.Status status = RemoteBatchResult.Status.fromWire(
                        requiredString(item, "status"));
                String error = optionalString(item, "error");
                if (status == RemoteBatchResult.Status.REJECTED) {
                    if (error == null || error.trim().isEmpty()) {
                        throw new IllegalArgumentException("rejected result missing error");
                    }
                } else if (item.containsKey("error")) {
                    throw new IllegalArgumentException("successful result has error");
                }
                results.add(new RemoteBatchResult.EventResult(
                        exactInt(item.get("index")),
                        requiredString(item, "event_id"), status, error));
            }
            int accepted = exactInt(receipt.get("accepted"));
            int duplicate = exactInt(receipt.get("duplicate"));
            int rejected = exactInt(receipt.get("rejected"));
            validateResults(results, eventIds, accepted, duplicate, rejected);
            return new RemoteBatchResult(results, accepted, duplicate, rejected);
        } catch (RuntimeException failure) {
            throw new RemoteProtocolException("Remote returned invalid batch result");
        }
    }

    private static void requireKeys(Map<?, ?> value, String... keys) {
        if (value.size() != keys.length) throw new IllegalArgumentException("unexpected field");
        for (String key : keys) {
            if (!value.containsKey(key)) throw new IllegalArgumentException("missing field");
        }
    }

    private static void requireOptionalKeys(
            Map<?, ?> value, String[] required, String[] optional) {
        for (String key : required) {
            if (!value.containsKey(key)) throw new IllegalArgumentException("missing field");
        }
        for (Object key : value.keySet()) {
            boolean allowed = false;
            for (String candidate : required) if (candidate.equals(key)) allowed = true;
            for (String candidate : optional) if (candidate.equals(key)) allowed = true;
            if (!allowed) throw new IllegalArgumentException("unexpected field");
        }
    }

    private static int exactInt(Object value) {
        if (!(value instanceof Long)) throw new IllegalArgumentException("integer field");
        long number = ((Long) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("integer field");
        }
        return (int) number;
    }

    private static String requiredString(Map<?, ?> value, String key) {
        Object result = value.get(key);
        if (!(result instanceof String)) throw new IllegalArgumentException("string field");
        return (String) result;
    }

    private static String optionalString(Map<?, ?> value, String key) {
        if (!value.containsKey(key) || value.get(key) == null) return null;
        return requiredString(value, key);
    }

    private static void validateResults(List<RemoteBatchResult.EventResult> results,
            List<String> eventIds, int accepted, int duplicate, int rejected) {
        if (results.size() != eventIds.size()
                || accepted < 0 || duplicate < 0 || rejected < 0
                || accepted + duplicate + rejected != results.size()) {
            throw new IllegalArgumentException("incomplete batch receipt");
        }
        boolean[] seen = new boolean[eventIds.size()];
        int acceptedCount = 0;
        int duplicateCount = 0;
        int rejectedCount = 0;
        for (RemoteBatchResult.EventResult result : results) {
            int index = result.index();
            if (index < 0 || index >= seen.length || seen[index]) {
                throw new IllegalArgumentException("invalid result index");
            }
            seen[index] = true;
            if (!result.eventId().equals(eventIds.get(index))) {
                throw new IllegalArgumentException("result event_id mismatch");
            }
            if (result.status() == RemoteBatchResult.Status.ACCEPTED) acceptedCount++;
            else if (result.status() == RemoteBatchResult.Status.DUPLICATE) duplicateCount++;
            else if (result.status() == RemoteBatchResult.Status.REJECTED) rejectedCount++;
        }
        if (accepted != acceptedCount || duplicate != duplicateCount
                || rejected != rejectedCount) {
            throw new IllegalArgumentException("result counters mismatch");
        }
    }

    private static List<String> eventIds(List<? extends RemoteEvent> events) {
        List<String> result = new ArrayList<String>(events.size());
        for (RemoteEvent event : events) {
            Object eventId = event.toMap().get("event_id");
            result.add(Texts.requireText(eventId == null ? null : String.valueOf(eventId),
                    "event_id"));
        }
        return result;
    }

    private static Map<?, ?> requiredObject(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("object field");
        return (Map<?, ?>) value;
    }

    private static boolean requiredBoolean(Map<?, ?> value, String key) {
        Object result = value.get(key);
        if (!(result instanceof Boolean)) throw new IllegalArgumentException("boolean field");
        return ((Boolean) result).booleanValue();
    }

    private static int requiredInt(Map<?, ?> value, String key) {
        if (!value.containsKey(key)) throw new IllegalArgumentException("missing field");
        return exactInt(value.get(key));
    }

    private static long requiredLong(Map<?, ?> value, String key) {
        Object result = value.get(key);
        if (!(result instanceof Long)) throw new IllegalArgumentException("integer field");
        return ((Long) result).longValue();
    }

    private static Level parseLevel(String value) {
        if ("trace".equals(value)) return Level.FINEST;
        if ("debug".equals(value)) return Level.FINE;
        if ("info".equals(value)) return Level.INFO;
        if ("warn".equals(value)) return Level.WARNING;
        if ("error".equals(value)) return Level.SEVERE;
        throw new IllegalArgumentException("unsupported level");
    }

    private static String settingsKeyStatus(String value) {
        if (!"active".equals(value) && !"paused".equals(value) && !"revoked".equals(value)) {
            throw new IllegalArgumentException("invalid key status");
        }
        return value;
    }

    private static String settingsEnvironment(String value) {
        if (!"production".equals(value) && !"staging".equals(value)
                && !"development".equals(value)) {
            throw new IllegalArgumentException("invalid environment");
        }
        return value;
    }
}
