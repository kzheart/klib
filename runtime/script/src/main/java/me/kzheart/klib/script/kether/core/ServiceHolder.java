/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

/** 为兼容上游本地化异常而保留的全局服务钩子。 */
public final class ServiceHolder {
    private static volatile QuestService<?> questServiceInstance;
    private ServiceHolder() { }
    public static void setQuestServiceInstance(QuestService<?> service) { questServiceInstance = service; }
    public static QuestService<?> getQuestServiceInstance() { return questServiceInstance; }
}
