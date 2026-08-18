package me.kzheart.klib.remote;

import java.util.UUID;

/** 上报幂等键的生成器。 */
final class EventIds {
    private EventIds() {
    }

    /**
     * 生成一个 32 位小写十六进制幂等键。
     *
     * <p>必须在构造上报体时调用一次，之后随负载持久化并在重试时原样复用，
     * 绝不能在每次发送前重新生成。
     */
    static String next() {
        UUID random = UUID.randomUUID();
        return hex(random.getMostSignificantBits()) + hex(random.getLeastSignificantBits());
    }

    private static String hex(long value) {
        StringBuilder text = new StringBuilder(Long.toHexString(value));
        while (text.length() < 16) {
            text.insert(0, '0');
        }
        return text.toString();
    }
}
