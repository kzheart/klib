/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

/** 保留注册回调的 Java 平台泛型空性；数据与命名空间策略由 Kotlin RegistryState 管理。 */
public final class DefaultRegistry implements QuestRegistry {
    private final RegistryState state = new RegistryState();
    @Override public synchronized void registerAction(String namespace, String id, QuestActionParser parser) {
        state.registerAction(namespace, id, parser);
    }
    @Override public void registerAction(String id, QuestActionParser parser) { registerAction("kether", id, parser); }
    @Override public synchronized void registerStringProcessor(String id, BiFunction<QuestContext.Frame, String, String> processor) {
        state.registerStringProcessor(id, processor);
    }
    @Override public void unregisterAction(String id) { unregisterAction("kether", id); }
    @Override public synchronized void unregisterAction(String namespace, String id) { state.unregisterAction(namespace, id); }
    @Override public synchronized Collection<String> getRegisteredActions(String namespace) { return state.getRegisteredActions(namespace); }
    @Override public Collection<String> getRegisteredActions() { return getRegisteredActions("kether"); }
    @Override public synchronized Collection<String> getRegisteredNamespace() { return state.getRegisteredNamespace(); }
    @Override public Optional<QuestActionParser> getParser(String id, String namespace) { return state.getParser(id, namespace); }
    @Override public Optional<QuestActionParser> getParser(String id, List<String> namespaces) { return state.getParser(id, namespaces); }
    @Override public Optional<QuestActionParser> getParser(String id) { return getParser(id, "kether"); }
    @Override public synchronized Optional<BiFunction<QuestContext.Frame, String, String>> getStringProcessor(String id) {
        return state.getStringProcessor(id);
    }
    @Override public synchronized void setFallbackParser(BiFunction<String, List<String>, QuestActionParser> parser) {
        state.setFallbackParser(parser);
    }
}
