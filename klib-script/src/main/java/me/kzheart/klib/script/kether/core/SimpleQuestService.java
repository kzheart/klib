package me.kzheart.klib.script.kether.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

/** 用于加载和执行 Java Kether 任务的无依赖服务。 */
public final class SimpleQuestService implements QuestService<SimpleQuestContext>, AutoCloseable {

    private final QuestRegistry registry;
    private final QuestLoader loader;
    private final Executor executor;
    private final ScheduledExecutorService asyncExecutor;
    private final boolean ownsAsyncExecutor;
    private final boolean toleranceParser;
    private final Map<String, Quest> quests = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> settings = new LinkedHashMap<>();
    private final Map<String, List<SimpleQuestContext>> running = new LinkedHashMap<>();

    public SimpleQuestService() {
        this(Runnable::run, newDaemonScheduler(), true, false);
    }

    /** 创建一个使用给定执行器运行异步动作续接的服务。 */
    public SimpleQuestService(Executor executor) {
        this(executor, newDaemonScheduler(), true, false);
    }

    public SimpleQuestService(
            Executor executor, ScheduledExecutorService asyncExecutor, boolean toleranceParser) {
        this(executor, asyncExecutor, false, toleranceParser);
    }

    private SimpleQuestService(
            Executor executor,
            ScheduledExecutorService asyncExecutor,
            boolean ownsAsyncExecutor,
            boolean toleranceParser) {
        this.registry = new DefaultRegistry();
        this.loader = new SimpleQuestLoader();
        this.executor = Objects.requireNonNull(executor, "executor");
        this.asyncExecutor = Objects.requireNonNull(asyncExecutor, "asyncExecutor");
        this.ownsAsyncExecutor = ownsAsyncExecutor;
        this.toleranceParser = toleranceParser;
        ServiceHolder.setQuestServiceInstance(this);
    }

    public Quest load(String id, String source, List<String> namespaces) {
        Quest quest = loader.load(this, id, source.getBytes(StandardCharsets.UTF_8), namespaces);
        synchronized (quests) {
            quests.put(id, quest);
        }
        return quest;
    }

    public Quest load(String id, String source) {
        return load(id, source, Collections.emptyList());
    }

    public SimpleQuestContext newContext(Quest quest) {
        return new SimpleQuestContext(this, quest);
    }

    @Override public QuestRegistry getRegistry() { return registry; }
    @Override public Optional<Quest> getQuest(String id) {
        synchronized (quests) { return Optional.ofNullable(quests.get(id)); }
    }
    @Override public Map<String, Object> getQuestSettings(String id) {
        synchronized (settings) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(
                    settings.computeIfAbsent(id, ignored -> new LinkedHashMap<>())));
        }
    }
    @Override public Map<String, Quest> getQuests() {
        synchronized (quests) { return Collections.unmodifiableMap(new LinkedHashMap<>(quests)); }
    }

    @Override
    public void startQuest(SimpleQuestContext context) {
        synchronized (running) {
            running.computeIfAbsent(context.getQuest().getId(), ignored -> new CopyOnWriteArrayList<>()).add(context);
        }
        context.runActions().whenComplete((value, error) -> terminateQuest(context));
    }

    @Override
    public void terminateQuest(SimpleQuestContext context) {
        synchronized (running) {
            List<SimpleQuestContext> contexts = running.get(context.getQuest().getId());
            if (contexts != null) {
                contexts.remove(context);
                if (contexts.isEmpty()) running.remove(context.getQuest().getId());
            }
        }
    }

    @Override public Map<String, List<SimpleQuestContext>> getRunningQuests() {
        synchronized (running) {
            Map<String, List<SimpleQuestContext>> snapshot = new LinkedHashMap<>();
            running.forEach((key, value) -> snapshot.put(key,
                    Collections.unmodifiableList(new ArrayList<>(value))));
            return Collections.unmodifiableMap(snapshot);
        }
    }
    @Override public List<SimpleQuestContext> getRunningQuests(String key) {
        synchronized (running) {
            List<SimpleQuestContext> contexts = running.get(key);
            return contexts == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(contexts));
        }
    }
    @Override public Executor getExecutor() { return executor; }
    @Override public ScheduledExecutorService getAsyncExecutor() { return asyncExecutor; }
    @Override public String getLocalizedText(String node, Object... params) {
        return node + (params.length == 0 ? "" : " " + Arrays.toString(params));
    }
    @Override public boolean isToleranceParser() { return toleranceParser; }

    @Override
    public void close() {
        if (ownsAsyncExecutor) asyncExecutor.shutdownNow();
        if (ServiceHolder.getQuestServiceInstance() == this) ServiceHolder.setQuestServiceInstance(null);
    }

    private static ScheduledExecutorService newDaemonScheduler() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "klib-kether-core");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
    }
}
