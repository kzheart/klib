/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

/** 感知命名空间的动作与字符串处理器注册表。 */
public interface QuestRegistry {
    void registerAction(String id, QuestActionParser parser);
    void registerAction(String namespace, String id, QuestActionParser parser);
    void registerStringProcessor(String id, BiFunction<QuestContext.Frame, String, String> processor);
    void unregisterAction(String id);
    void unregisterAction(String namespace, String id);
    Collection<String> getRegisteredActions(String namespace);
    Collection<String> getRegisteredActions();
    Collection<String> getRegisteredNamespace();
    Optional<QuestActionParser> getParser(String id, List<String> namespace);
    Optional<QuestActionParser> getParser(String id, String namespace);
    Optional<QuestActionParser> getParser(String id);
    Optional<BiFunction<QuestContext.Frame, String, String>> getStringProcessor(String id);
    void setFallbackParser(BiFunction<String, List<String>, QuestActionParser> parser);
}
