package me.kzheart.klib.script;

/** 宿主发送者查询；玩家身份与发送者名称不能由对象 toString 推测。 */
public interface ScriptSenderQuery {
    boolean isPlayer(Object sender);
    String name(Object sender);

    /** 玩家是否仍在线；{@code wait} 结束时玩家已离线则停止脚本。默认视为在线。 */
    default boolean isOnline(Object sender) { return true; }
}
