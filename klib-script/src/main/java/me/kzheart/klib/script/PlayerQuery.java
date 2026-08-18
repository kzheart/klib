package me.kzheart.klib.script;

/** Kether 条件动作使用的宿主玩家属性。 */
public interface PlayerQuery {

    Object property(Object sender, String name);

    boolean hasPermission(Object sender, String permission);
}
