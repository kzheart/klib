package me.kzheart.klib.hook;

import java.util.Objects;

/** 钩子实现共用的文本校验工具。 */
public final class Texts {

    private Texts() {
    }

    /** 返回去除首尾空白后的值，并拒绝 null 或空白输入。 */
    public static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }
}
