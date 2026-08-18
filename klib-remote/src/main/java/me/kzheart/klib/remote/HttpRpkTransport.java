package me.kzheart.klib.remote;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/** Remote `/ingest/v1` 的 Java 8 HTTP 客户端。 */
final class HttpRpkTransport implements RpkTransport {
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final Pattern PUBLIC_KEY = Pattern.compile(
            "^rpk_(?:live|test)_[A-Za-z0-9_-]{43}$");
    private final URL endpoint;
    private final String publicKey;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    HttpRpkTransport(String endpoint, String publicKey, boolean insecureLoopback) {
        this(endpoint, publicKey, insecureLoopback, 5000, 10000);
    }

    HttpRpkTransport(String endpoint, String publicKey, boolean insecureLoopback,
            int connectTimeoutMillis, int readTimeoutMillis) {
        this.endpoint = parse(endpoint, insecureLoopback);
        if (publicKey == null || !PUBLIC_KEY.matcher(publicKey).matches()) {
            throw new IllegalArgumentException(
                    "publicKey must be rpk_live_ or rpk_test_ followed by 43 URL-safe characters");
        }
        if (connectTimeoutMillis < 1 || readTimeoutMillis < 1) {
            throw new IllegalArgumentException("HTTP timeouts must be positive");
        }
        this.publicKey = publicKey;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    @Override public String settings() throws IOException {
        return new String(request("GET", "/ingest/v1/settings", null, 200),
                StandardCharsets.UTF_8);
    }

    @Override public String sendBatch(byte[] json) throws IOException {
        return new String(request("POST", "/ingest/v1/batches", gzip(json), 202),
                StandardCharsets.UTF_8);
    }

    @Override public String queueIdentity() {
        return endpoint.toExternalForm() + '\u0000' + publicKey;
    }

    private byte[] request(String method, String path, byte[] body, int expected) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint.toString() + path)
                .openConnection();
        try {
            connection.setConnectTimeout(connectTimeoutMillis);
            connection.setReadTimeout(readTimeoutMillis);
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization", "Bearer " + publicKey);
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Content-Encoding", "gzip");
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            }
            int status = connection.getResponseCode();
            byte[] response = readLimited(status >= 400
                    ? connection.getErrorStream() : connection.getInputStream());
            if (status != expected) {
                throw new RemoteHttpException(status,
                        retryAfterMillis(connection.getHeaderField("Retry-After")));
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private static long retryAfterMillis(String value) {
        if (value == null) return -1L;
        String normalized = value.trim();
        try {
            long seconds = Long.parseLong(normalized);
            if (seconds < 0L) return -1L;
            return seconds > Long.MAX_VALUE / 1000L ? Long.MAX_VALUE : seconds * 1000L;
        } catch (NumberFormatException ignored) {
            try {
                long target = ZonedDateTime.parse(normalized,
                        DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                return Math.max(0L, target - System.currentTimeMillis());
            } catch (DateTimeParseException invalidDate) {
                return -1L;
            }
        }
    }

    private static byte[] gzip(byte[] input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(output);
        gzip.write(input);
        gzip.close();
        return output.toByteArray();
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        if (input == null) return new byte[0];
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) throw new IOException("Remote response exceeds limit");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally { input.close(); }
    }

    private static URL parse(String value, boolean insecureLoopback) {
        if (value == null) throw new IllegalArgumentException("endpoint must not be null");
        String normalized = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        try {
            URL url = new URL(normalized);
            boolean secure = "https".equals(url.getProtocol());
            boolean loopback = insecureLoopback && "http".equals(url.getProtocol())
                    && ("127.0.0.1".equals(url.getHost())
                    || "localhost".equalsIgnoreCase(url.getHost())
                    || "::1".equals(url.getHost()));
            if (!secure && !loopback) throw new IllegalArgumentException("endpoint must use https");
            if (url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null) {
                throw new IllegalArgumentException(
                        "endpoint must not contain user info, query, or fragment");
            }
            return url;
        } catch (MalformedURLException failure) {
            throw new IllegalArgumentException("invalid endpoint", failure);
        }
    }
}
