package me.kzheart.klib.script.kether.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

/** 保留 Java getter 的方法和 Kotlin synthetic property 双源码入口。 */
public final class SimpleQuestService implements QuestService<SimpleQuestContext>, AutoCloseable {
    private final QuestServiceState state;

    public SimpleQuestService() { this(Runnable::run, QuestServiceState.newDaemonScheduler(), true, false); }
    public SimpleQuestService(boolean toleranceParser) {
        this(Runnable::run, QuestServiceState.newDaemonScheduler(), true, toleranceParser);
    }
    public SimpleQuestService(Executor executor) { this(executor, QuestServiceState.newDaemonScheduler(), true, false); }
    public SimpleQuestService(Executor executor, ScheduledExecutorService asyncExecutor, boolean toleranceParser) {
        this(executor, asyncExecutor, false, toleranceParser);
    }
    private SimpleQuestService(Executor executor, ScheduledExecutorService asyncExecutor,
            boolean ownsAsyncExecutor, boolean toleranceParser) {
        state = new QuestServiceState(this, Objects.requireNonNull(executor, "executor"),
                Objects.requireNonNull(asyncExecutor, "asyncExecutor"), ownsAsyncExecutor, toleranceParser);
        ServiceHolder.setQuestServiceInstance(this);
    }
    public Quest load(String id, String source, List<String> namespaces) { return state.load(id, source, namespaces); }
    public Quest load(String id, String source) { return load(id, source, Collections.emptyList()); }
    public SimpleQuestContext newContext(Quest quest) { return new SimpleQuestContext(this, quest); }
    @Override public QuestRegistry getRegistry() { return state.getRegistry(); }
    @Override public Optional<Quest> getQuest(String id) { return state.getQuest(id); }
    @Override public Map<String, Object> getQuestSettings(String id) { return state.getQuestSettings(id); }
    @Override public Map<String, Quest> getQuests() { return state.getQuests(); }
    @Override public void startQuest(SimpleQuestContext context) { state.startQuest(context); }
    @Override public void terminateQuest(SimpleQuestContext context) { state.terminateQuest(context); }
    @Override public Map<String, List<SimpleQuestContext>> getRunningQuests() { return state.getRunningQuests(); }
    @Override public List<SimpleQuestContext> getRunningQuests(String key) { return state.getRunningQuests(key); }
    @Override public Executor getExecutor() { return state.getExecutor(); }
    @Override public ScheduledExecutorService getAsyncExecutor() { return state.getAsyncExecutor(); }
    @Override public String getLocalizedText(String node, Object... params) {
        return node + (params.length == 0 ? "" : " " + Arrays.toString(params));
    }
    @Override public boolean isToleranceParser() { return state.getToleranceParser(); }
    @Override public PropertyAccessor getPropertyAccessor() { return state.getPropertyAccessor(); }
    public void setPropertyAccessor(PropertyAccessor value) { state.setPropertyAccessor(value); }
    @Override public void close() { state.close(); }
}
