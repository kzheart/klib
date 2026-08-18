package me.kzheart.klib.remote;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/** 基于异常类型、cause 与规范化堆栈生成稳定 Issue fingerprint。 */
public final class IssueFingerprint {
    private IssueFingerprint() { }

    public static String of(Throwable error) {
        Objects.requireNonNull(error, "error");
        StringBuilder normalized = new StringBuilder();
        Throwable current = error;
        int causes = 0;
        while (current != null && causes < 32) {
            normalized.append(current.getClass().getName()).append('\n');
            StackTraceElement[] stack = current.getStackTrace();
            for (StackTraceElement frame : stack) {
                normalized.append(frame.getClassName()).append('#')
                        .append(frame.getMethodName()).append('(')
                        .append(frame.getFileName()).append(")\n");
            }
            current = current.getCause();
            causes++;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(32);
            for (int index = 0; index < 16; index++) {
                result.append(String.format(java.util.Locale.ROOT, "%02x", digest[index] & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
