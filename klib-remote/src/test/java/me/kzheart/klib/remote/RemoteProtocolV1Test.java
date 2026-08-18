package me.kzheart.klib.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteProtocolV1Test {
    private static final String VALID_SETTINGS = "{\"schema_version\":1,"
            + "\"key_status\":\"active\",\"environment\":\"production\","
            + "\"accepting_events\":true,\"policy\":{\"paused\":false,"
            + "\"exceptions\":true,\"logs\":true,\"manual_incidents\":true,"
            + "\"minimum_level\":\"info\",\"sample_rate\":100},\"limits\":{"
            + "\"max_compressed_bytes\":262144,\"max_decompressed_bytes\":1048576,"
            + "\"max_batch_events\":100,\"max_event_bytes\":262144,"
            + "\"max_message_bytes\":200000,\"max_attributes\":32,"
            + "\"max_installation_id_bytes\":128,\"max_event_id_bytes\":128,"
            + "\"max_attribute_key_bytes\":64,\"max_attribute_value_bytes\":1024,"
            + "\"max_environment_field_bytes\":128,\"key_events_per_minute\":1000,"
            + "\"ip_events_per_minute\":2000,"
            + "\"installation_events_per_minute\":1000,"
            + "\"asn_events_per_minute\":5000,"
            + "\"product_spike_events_per_minute\":10000},\"retention\":{"
            + "\"logs_days\":7,\"incidents_days\":30,\"aggregates_days\":90}}";

    @TempDir
    Path temporaryDirectory;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void effectivePolicyCannotExceedBuildCapabilities() {
        RemoteCapabilities build = RemoteCapabilities.builder()
                .exceptions(true)
                .logs(false)
                .manualIncidents(false)
                .build();
        RemotePolicy requested = RemotePolicy.builder()
                .exceptions(true)
                .logs(true)
                .manualIncidents(true)
                .minimumLevel(java.util.logging.Level.FINE)
                .sampleRate(75)
                .build();

        RemotePolicy effective = requested.restrictTo(build);

        assertTrue(effective.exceptions());
        assertFalse(effective.logs());
        assertFalse(effective.manualIncidents());
        assertEquals(75, effective.sampleRate());
    }

    @Test
    void productInstallationIdIsRandomPersistentAndProductScoped() throws Exception {
        InstallationId first = InstallationId.forProduct("dev.market", temporaryDirectory);
        String firstValue = first.get();

        assertEquals(firstValue,
                InstallationId.forProduct("dev.market", temporaryDirectory).get());
        assertNotEquals(firstValue,
                InstallationId.forProduct("dev.chat", temporaryDirectory).get());
        assertTrue(Files.isRegularFile(temporaryDirectory
                .resolve(".klib-remote")
                .resolve("installation-" + InstallationId.productFileKey("dev.market"))));
    }

    @Test
    void publicKeyMustMatchVersionOneGrammar() {
        RemoteCapabilities capabilities = RemoteCapabilities.builder().build();
        RemoteEnvironment environment = new RemoteEnvironment("1", "Paper", "8", "Linux");
        assertThrows(IllegalArgumentException.class, () -> RemoteClient.insecureLoopback(
                "http://127.0.0.1:12345", "rpk_test_short", capabilities,
                InstallationId.forProduct("dev.market", temporaryDirectory), environment));
    }

    @Test
    void directHttpClientRejectsEndpointUserInfoWithoutEchoingIt() {
        String secret = "endpoint-userinfo-secret";

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new HttpRpkTransport(
                        "https://user:" + secret + "@cloud.example.com",
                        "rpk_test_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG", false));

        assertFalse(String.valueOf(failure.getMessage()).contains(secret));
    }

    @Test
    void policyRemainsFailClosedUntilSettingsAreSuccessfullyFetched() {
        RemoteCapabilities capabilities = RemoteCapabilities.builder()
                .exceptions(true).logs(true).manualIncidents(true).build();
        RemoteClient client = new RemoteClient(new RpkTransport() {
            @Override public String settings() throws IOException {
                throw new IOException("offline");
            }
            @Override public String sendBatch(byte[] json) { return ""; }
        }, capabilities, () -> "inst-test",
                new RemoteEnvironment("1", "Paper", "8", "Linux"));

        assertTrue(client.policy().paused());
        assertFalse(client.policy().exceptions());
        assertFalse(client.policy().logs());
        assertFalse(client.policy().manualIncidents());
        assertThrows(IOException.class, client::refreshPolicy);
        assertTrue(client.policy().paused());
    }

    @Test
    void httpRateLimitExposesRetryAfterWithoutReadingOrTruncatingRequestBody() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ingest/v1/batches", exchange -> {
            read(new GZIPInputStream(exchange.getRequestBody()));
            exchange.getResponseHeaders().set("Retry-After", "2");
            respond(exchange, 429, "{\"error\":\"rate_limited\"}");
        });
        server.start();
        HttpRpkTransport transport = new HttpRpkTransport(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "rpk_test_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG", true);

        RemoteHttpException failure = assertThrows(RemoteHttpException.class,
                () -> transport.sendBatch("complete-body".getBytes(StandardCharsets.UTF_8)));
        assertEquals(429, failure.status());
        assertEquals(2000L, failure.retryAfterMillis());
    }

    @Test
    void deterministicLogSamplingMatchesCrossLanguageVector() throws Exception {
        Path vectors = Paths.get(System.getProperty("klib.remote.samplingVectors"));
        List<String> lines = Files.readAllLines(vectors, StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] fields = line.split("\\t", -1);
            assertEquals(3, fields.length, "invalid sampling vector line " + (index + 1));
            int rate = Integer.parseInt(fields[1]);
            boolean expected = Boolean.parseBoolean(fields[2]);
            assertEquals(expected, RemoteSampling.accepts(fields[0], rate), line);
        }
    }

    @Test
    void clientUsesRpkRoutesAndPostsVersionedBatch() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<String>();
        AtomicReference<String> body = new AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ingest/v1/settings", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, VALID_SETTINGS
                    .replace("\"manual_incidents\":true",
                            "\"manual_incidents\":false")
                    .replace("\"minimum_level\":\"info\"",
                            "\"minimum_level\":\"warn\"")
                    .replace("\"sample_rate\":100", "\"sample_rate\":40")
                    .replace("\"max_event_bytes\":262144", "\"max_event_bytes\":65536")
                    .replace("\"max_message_bytes\":200000", "\"max_message_bytes\":32768"));
        });
        server.createContext("/ingest/v1/batches", exchange -> {
            assertEquals("gzip", exchange.getRequestHeaders().getFirst("Content-Encoding"));
            body.set(read(new GZIPInputStream(exchange.getRequestBody())));
            String eventId = eventIds(body.get()).get(0);
            respond(exchange, 202, "{\"results\":[{\"index\":0,\"event_id\":\""
                    + eventId + "\",\"status\":\"accepted\"}],"
                    + "\"accepted\":1,\"duplicate\":0,\"rejected\":0}");
        });
        server.start();

        RemoteCapabilities build = RemoteCapabilities.builder()
                .exceptions(true)
                .logs(false)
                .build();
        RemoteClient client = RemoteClient.insecureLoopback(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "rpk_test_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG",
                build,
                InstallationId.forProduct("dev.market", temporaryDirectory),
                new RemoteEnvironment("2.4.0", "Paper 1.21.4", "21.0.4", "Linux"));

        RemotePolicy effective = client.refreshPolicy();
        assertEquals("Bearer rpk_test_0123456789abcdefghijklmnopqrstuvwxyzABCDEFG",
                authorization.get());
        assertTrue(effective.exceptions());
        assertFalse(effective.logs(), "远端不能开启构建期未允许的日志能力");
        assertEquals(java.util.logging.Level.WARNING, effective.minimumLevel());
        assertEquals(30, client.settings().retention().incidentsDays());
        assertEquals(1000, client.settings().limits().keyEventsPerMinute());
        assertEquals(2000, client.settings().limits().ipEventsPerMinute());
        assertEquals(1000, client.settings().limits().installationEventsPerMinute());
        assertEquals(5000, client.settings().limits().asnEventsPerMinute());
        assertEquals(10000, client.settings().limits().productSpikeEventsPerMinute());

        RemoteBatchResult result = client.sendBatch(Collections.<RemoteEvent>singletonList(
                RemoteEvent.of("log", Collections.<String, Object>singletonMap("message", "raw"))));
        assertEquals(1, result.accepted());
        assertEquals(RemoteBatchResult.Status.ACCEPTED, result.results().get(0).status());
        assertTrue(body.get().contains("\"schema_version\":1"), body.get());
        assertTrue(body.get().contains("\"installation_id\":"), body.get());
        assertTrue(body.get().contains("\"events\":["), body.get());
        assertTrue(body.get().contains("\"message\":\"raw\""), body.get());
    }

    @Test
    void settingsRejectsUnknownDuplicateTrailingMisplacedAndWrongTypeFields() {
        assertInvalidSettings(VALID_SETTINGS.replace(
                "\"schema_version\":1", "\"schema_version\":1,\"unknown\":true"));
        assertInvalidSettings(VALID_SETTINGS.replace(
                "\"paused\":false", "\"paused\":false,\"paused\":true"));
        assertInvalidSettings(VALID_SETTINGS + " true");
        assertInvalidSettings(VALID_SETTINGS
                .replace("\"sample_rate\":100", "\"wrong_layer\":100")
                .replace("\"accepting_events\":true",
                        "\"accepting_events\":true,\"sample_rate\":100"));
        assertInvalidSettings(VALID_SETTINGS.replace(
                "\"accepting_events\":true", "\"accepting_events\":\"true\""));
    }

    @Test
    void settingsRequiresAndValidatesEveryPolicyLimitsAndRetentionField() {
        assertInvalidSettings(VALID_SETTINGS.replace("\"logs\":true,", ""));
        assertInvalidSettings(VALID_SETTINGS.replace("\"sample_rate\":100",
                "\"sample_rate\":101"));
        assertInvalidSettings(VALID_SETTINGS.replace("\"minimum_level\":\"info\"",
                "\"minimum_level\":\"verbose\""));
        assertInvalidSettings(VALID_SETTINGS.replace("\"minimum_level\":\"info\"",
                "\"minimum_level\":\"INFO\""));
        assertInvalidSettings(VALID_SETTINGS.replace("\"key_events_per_minute\":1000,", ""));
        assertInvalidSettings(VALID_SETTINGS.replace("\"max_event_bytes\":262144",
                "\"max_event_bytes\":0"));
        assertInvalidSettings(VALID_SETTINGS.replace("\"max_batch_events\":100",
                "\"max_batch_events\":1001"));
        assertInvalidSettings(VALID_SETTINGS.replace("\"logs_days\":7,", ""));
        assertInvalidSettings(VALID_SETTINGS.replace("\"incidents_days\":30",
                "\"incidents_days\":6"));
    }

    @Test
    void batchReceiptRejectsTextFragmentsAndSchemaExtensions() {
        RemoteCapabilities capabilities = RemoteCapabilities.builder().logs(true).build();
        RemoteEnvironment environment = new RemoteEnvironment("1", "Paper", "8", "Linux");
        RemoteEvent event = RemoteEvent.of("log",
                Collections.<String, Object>singletonMap("message", "raw"));
        String eventId = String.valueOf(event.toMap().get("event_id"));

        assertInvalidReceipt("prefix {\"results\":[{\"index\":0,\"event_id\":\""
                + eventId + "\",\"status\":\"accepted\"}],\"accepted\":1,"
                + "\"duplicate\":0,\"rejected\":0} suffix", capabilities, environment, event);
        assertInvalidReceipt("{\"results\":[{\"index\":0,\"event_id\":\"" + eventId + "\","
                + "\"status\":\"accepted\",\"unexpected\":true}],\"accepted\":1,"
                + "\"duplicate\":0,\"rejected\":0}", capabilities, environment, event);
        assertInvalidReceipt("{\"results\":[{\"index\":0,\"event_id\":\"" + eventId + "\","
                + "\"status\":\"accepted\"}],\"accepted\":1,\"accepted\":1,"
                + "\"duplicate\":0,\"rejected\":0}", capabilities, environment, event);
        assertInvalidReceipt("{\"results\":[{\"index\":0,\"event_id\":\"" + eventId + "\","
                + "\"status\":\"accepted\",\"status\":\"accepted\"}],\"accepted\":1,"
                + "\"duplicate\":0,\"rejected\":0}", capabilities, environment, event);
        assertInvalidReceipt("{\"results\":[{\"index\":\"0\",\"event_id\":\"" + eventId + "\","
                + "\"status\":\"accepted\"}],\"accepted\":1,"
                + "\"duplicate\":0,\"rejected\":0}", capabilities, environment, event);
        assertInvalidReceipt("{\"results\":[{\"index\":1,\"event_id\":\"" + eventId + "\","
                + "\"status\":\"accepted\"}],\"accepted\":1,"
                + "\"duplicate\":0,\"rejected\":0}", capabilities, environment, event);
        assertInvalidReceipt("{\"results\":[{\"index\":0,\"event_id\":\"" + eventId + "\","
                + "\"status\":\"accepted\"}],\"accepted\":0,"
                + "\"duplicate\":1,\"rejected\":0}", capabilities, environment, event);
    }

    @Test
    void batchReceiptRequiresMatchingEventIdsAndStatusSpecificErrors() {
        RemoteCapabilities capabilities = RemoteCapabilities.builder().logs(true).build();
        RemoteEnvironment environment = new RemoteEnvironment("1", "Paper", "8", "Linux");
        RemoteEvent event = RemoteEvent.of("log",
                Collections.<String, Object>singletonMap("message", "raw"));
        String eventId = String.valueOf(event.toMap().get("event_id"));

        assertInvalidReceipt("{\"results\":[{\"index\":0,\"status\":\"accepted\"}],"
                + "\"accepted\":1,\"duplicate\":0,\"rejected\":0}",
                capabilities, environment, event);
        assertInvalidReceipt(receipt(eventId, "accepted", "must-not-exist"),
                capabilities, environment, event);
        assertInvalidReceipt("{\"results\":[{\"index\":0,\"event_id\":\"" + eventId + "\","
                + "\"status\":\"accepted\",\"error\":null}],\"accepted\":1,"
                + "\"duplicate\":0,\"rejected\":0}", capabilities, environment, event);
        assertInvalidReceipt(receipt(eventId, "duplicate", "must-not-exist"),
                capabilities, environment, event);
        assertInvalidReceipt(receipt(eventId, "rejected", null),
                capabilities, environment, event);
        assertInvalidReceipt(receipt(eventId, "rejected", ""),
                capabilities, environment, event);
        assertInvalidReceipt(receipt("evt_mismatch", "accepted", null),
                capabilities, environment, event);
    }

    private static String receipt(String eventId, String status, String error) {
        String errorField = error == null ? "" : ",\"error\":\"" + error + "\"";
        int accepted = "accepted".equals(status) ? 1 : 0;
        int duplicate = "duplicate".equals(status) ? 1 : 0;
        int rejected = "rejected".equals(status) ? 1 : 0;
        return "{\"results\":[{\"index\":0,\"event_id\":\"" + eventId
                + "\",\"status\":\"" + status + "\"" + errorField + "}],"
                + "\"accepted\":" + accepted + ",\"duplicate\":" + duplicate
                + ",\"rejected\":" + rejected + "}";
    }

    private static void assertInvalidSettings(String settings) {
        RemoteCapabilities capabilities = RemoteCapabilities.builder()
                .logs(true).exceptions(true).manualIncidents(true).build();
        RemoteClient client = new RemoteClient(new RpkTransport() {
            @Override public String settings() { return settings; }
            @Override public String sendBatch(byte[] json) { return ""; }
        }, capabilities, () -> "inst-test",
                new RemoteEnvironment("1", "Paper", "8", "Linux"));
        assertThrows(RemoteProtocolException.class, client::refreshPolicy);
    }

    @SuppressWarnings("unchecked")
    private static List<String> eventIds(String batch) {
        Map<String, Object> decoded = (Map<String, Object>) StrictJsonParser.parse(batch);
        List<Object> events = (List<Object>) decoded.get("events");
        List<String> result = new ArrayList<String>(events.size());
        for (Object encoded : events) {
            result.add((String) ((Map<String, Object>) encoded).get("event_id"));
        }
        return result;
    }

    private static void assertInvalidReceipt(String receipt, RemoteCapabilities capabilities,
            RemoteEnvironment environment, RemoteEvent event) {
        RemoteClient client = new RemoteClient(new RpkTransport() {
            @Override public String settings() { return ""; }
            @Override public String sendBatch(byte[] json) { return receipt; }
        }, capabilities, () -> "inst-test", environment);
        assertThrows(RemoteProtocolException.class,
                () -> client.sendBatch(Collections.singletonList(event)));
    }

    private static String read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
