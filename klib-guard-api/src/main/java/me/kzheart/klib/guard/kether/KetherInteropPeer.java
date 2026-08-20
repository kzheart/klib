package me.kzheart.klib.guard.kether;

/** 门户对单个外部 TabooLib OpenContainer 的父加载器代理。 */
public interface KetherInteropPeer {

    String name();

    KetherInteropResult call(String channel, Object... arguments);
}
