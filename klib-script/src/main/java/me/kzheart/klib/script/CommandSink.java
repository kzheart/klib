package me.kzheart.klib.script;

/** command 动作使用的宿主集成。 */
@FunctionalInterface
public interface CommandSink {

    Object dispatch(Object sender, String command);
}
