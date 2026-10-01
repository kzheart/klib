package me.kzheart.klib.script;

/** 宿主发送者查询；玩家身份与发送者名称不能由对象 toString 推测。 */
public interface ScriptSenderQuery {
    boolean isPlayer(Object sender);
    String name(Object sender);
}
