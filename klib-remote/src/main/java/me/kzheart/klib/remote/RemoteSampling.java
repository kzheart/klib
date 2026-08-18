package me.kzheart.klib.remote;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** v1 日志采样的跨语言确定性算法。Incident 不使用采样率。 */
final class RemoteSampling {
    private static final byte[] DOMAIN = "klib-remote-sample-v1\0"
            .getBytes(StandardCharsets.UTF_8);

    private RemoteSampling() {
    }

    static boolean accepts(String eventId, int sampleRate) {
        if (sampleRate <= 0) return false;
        if (sampleRate >= 100) return true;
        byte[] digest;
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update(DOMAIN);
            digest = hash.digest(Texts.requireText(eventId, "eventId")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        long value = ((long) (digest[0] & 0xff) << 24)
                | ((long) (digest[1] & 0xff) << 16)
                | ((long) (digest[2] & 0xff) << 8)
                | (long) (digest[3] & 0xff);
        return value % 100L < sampleRate;
    }
}
