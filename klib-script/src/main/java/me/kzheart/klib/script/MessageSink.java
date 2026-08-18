package me.kzheart.klib.script;

/** tell 动作使用的宿主集成。 */
@FunctionalInterface
public interface MessageSink {

    void send(Object sender, String message);
}
