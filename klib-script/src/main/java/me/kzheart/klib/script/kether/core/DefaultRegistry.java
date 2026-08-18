/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/** TabooLib 命名空间查找规则的内存实现。 */
public final class DefaultRegistry implements QuestRegistry {

    private final Map<String, Map<String, QuestActionParser>> parsers = new LinkedHashMap<>();
    private final Map<String, BiFunction<QuestContext.Frame, String, String>> processors = new LinkedHashMap<>();
    private BiFunction<String, List<String>, QuestActionParser> fallbackParser;

    @Override
    public synchronized void registerAction(String namespace, String id, QuestActionParser parser) {
        parsers.computeIfAbsent(namespace, ignored -> new LinkedHashMap<>()).put(id, parser);
    }

    @Override public void registerAction(String id, QuestActionParser parser) { registerAction("kether", id, parser); }
    @Override public synchronized void registerStringProcessor(
            String id, BiFunction<QuestContext.Frame, String, String> processor) { processors.put(id, processor); }
    @Override public void unregisterAction(String id) { unregisterAction("kether", id); }

    @Override
    public synchronized void unregisterAction(String namespace, String id) {
        Map<String, QuestActionParser> map = parsers.get(namespace);
        if (map == null) return;
        if ("*".equals(id)) map.clear(); else map.remove(id);
    }

    @Override
    public synchronized Collection<String> getRegisteredActions(String namespace) {
        Map<String, QuestActionParser> map = parsers.get(namespace);
        return map == null ? Collections.emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<>(map.keySet()));
    }

    @Override public Collection<String> getRegisteredActions() { return getRegisteredActions("kether"); }
    @Override public synchronized Collection<String> getRegisteredNamespace() {
        return Collections.unmodifiableList(new java.util.ArrayList<>(parsers.keySet()));
    }
    @Override public Optional<QuestActionParser> getParser(String id, String namespace) {
        Map<String, QuestActionParser> map = parsers.get(namespace);
        return map == null ? Optional.empty() : Optional.ofNullable(map.get(id));
    }

    @Override
    public Optional<QuestActionParser> getParser(String id, List<String> namespaces) {
        int separator = id.indexOf(':');
        if (separator > 0 && separator < id.length() - 1) {
            return getParser(id.substring(separator + 1), id.substring(0, separator));
        }
        for (String namespace : namespaces) {
            if (namespace == null) continue;
            Optional<QuestActionParser> parser = getParser(id, namespace);
            if (parser.isPresent()) return parser;
        }
        BiFunction<String, List<String>, QuestActionParser> fallback = fallbackParser;
        return fallback == null
                ? Optional.empty()
                : Optional.ofNullable(fallback.apply(
                        id, Collections.unmodifiableList(new java.util.ArrayList<>(namespaces))));
    }

    @Override public Optional<QuestActionParser> getParser(String id) { return getParser(id, "kether"); }
    @Override public synchronized Optional<BiFunction<QuestContext.Frame, String, String>> getStringProcessor(String id) {
        return Optional.ofNullable(processors.get(id));
    }
    @Override public synchronized void setFallbackParser(
            BiFunction<String, List<String>, QuestActionParser> parser) {
        fallbackParser = parser;
    }
}
