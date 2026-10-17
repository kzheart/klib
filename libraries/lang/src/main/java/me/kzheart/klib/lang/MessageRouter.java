package me.kzheart.klib.lang;

/** 消息管线的最终投递阶段。 */
public interface MessageRouter {
    void route(MessageRecipient recipient, RichText message);

    default void route(MessageRecipient recipient, MessageRoute route, RichText message) {
        route(recipient, message);
    }
}
