package me.kzheart.klib.lang;

/** 不暴露 Adventure、用于非聊天路由的可选平台适配器。 */
public interface MessageRouteSink {
    void send(MessageRoute route, RichText message);
}
