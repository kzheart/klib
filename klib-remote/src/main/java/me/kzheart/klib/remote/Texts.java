package me.kzheart.klib.remote;

/** 共用的参数校验工具。 */
final class Texts {
    private Texts() {
    }

    static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
