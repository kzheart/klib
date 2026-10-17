/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

/** Java Kether 核心所需的运行时服务。 */
public interface QuestService<C extends QuestContext> {
    QuestRegistry getRegistry();
    Optional<Quest> getQuest(String id);
    Map<String, Object> getQuestSettings(String id);
    Map<String, Quest> getQuests();
    void startQuest(C context);
    void terminateQuest(C context);
    Map<String, List<C>> getRunningQuests();
    List<C> getRunningQuests(String key);
    Executor getExecutor();
    ScheduledExecutorService getAsyncExecutor();
    String getLocalizedText(String node, Object... params);

    default boolean isToleranceParser() { return false; }

    /** 属性读取简写的解析器；未安装时属性读取语句失败。 */
    default PropertyAccessor getPropertyAccessor() { return null; }

    @SuppressWarnings("unchecked")
    static <C extends QuestContext> QuestService<C> instance() {
        return (QuestService<C>) ServiceHolder.getQuestServiceInstance();
    }
}
