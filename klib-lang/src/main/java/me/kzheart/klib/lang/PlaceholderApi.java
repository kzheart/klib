package me.kzheart.klib.lang;

/** 可选的外部占位符展开阶段。 */
public interface PlaceholderApi {
    String expand(MessageRecipient recipient, String text);
}
