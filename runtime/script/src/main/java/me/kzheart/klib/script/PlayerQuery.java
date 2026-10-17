package me.kzheart.klib.script;

/** Kether 条件动作使用的宿主玩家属性。 */
public interface PlayerQuery {

    /** 读取玩家属性；name 为小写、空格分隔的操作名，如 {@code level}、{@code block x}、{@code on ground}。 */
    Object property(Object sender, String name);

    boolean hasPermission(Object sender, String permission);

    /** 写入玩家属性，返回是否支持；默认只读。 */
    default boolean write(Object sender, String name, Method method, Object value) {
        return false;
    }

    /** {@code player 属性 to|add|sub 值} 的写入方式。 */
    enum Method { MODIFY, INCREASE, DECREASE }
}
