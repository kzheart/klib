/*
 * 派生自 TabooLib 的 taboolib.library.kether.AbstractQuestContext。
 * Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6
 */
package me.kzheart.klib.script.kether.core;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** 保留公开扩展点与 protected 字段；生命周期由 Kotlin QuestLifecycle 管理。 */
@SuppressWarnings("this-escape")
public abstract class AbstractQuestContext<T extends AbstractQuestContext<T>> implements QuestContext {

    /** 上下文快速失败前允许执行的默认动作数上限。 */
    public static final long DEFAULT_STEP_LIMIT = 100_000L;

    protected final QuestService<T> service;
    protected final Frame rootFrame;
    protected final Quest quest;
    protected final QuestExecutor executor;
    protected volatile ExitStatus exitStatus;
    protected volatile CompletableFuture<Object> future;
    private final QuestLifecycle lifecycle = new QuestLifecycle(this);

    protected AbstractQuestContext(QuestService<T> service, Quest quest, String playerIdentifier) {
        this.service = service;
        this.quest = quest;
        this.rootFrame = createRootFrame();
        this.executor = new QuestExecutor(this);
    }

    protected abstract Executor createExecutor();

    protected Frame createRootFrame() {
        return new SimpleNamedFrame(null, new LinkedList<>(), new SimpleVarTable(null), BASE_BLOCK, this);
    }

    @Override public QuestService<T> getService() { return service; }
    @Override public Quest getQuest() { return quest; }
    @Override public void setExitStatus(ExitStatus exitStatus) { this.exitStatus = exitStatus; }
    @Override public Optional<ExitStatus> getExitStatus() { return Optional.ofNullable(exitStatus); }
    @Override public QuestExecutor getExecutor() { return executor; }
    @Override public Frame rootFrame() { return rootFrame; }

    @Override public synchronized CompletableFuture<Object> runActions() { return lifecycle.runActions(); }

    /** 在首次执行前保留脱离根结果的 async 动作，并等待原生动作全部结束。 */
    public synchronized CompletableFuture<Void> retainDetachedActions() { return lifecycle.retainDetachedActions(); }
    @Override public synchronized void terminate() { lifecycle.terminate(); }
    final boolean isTerminated() { return lifecycle.isTerminated(); }
    final <R> CompletableFuture<R> track(CompletableFuture<R> action) { return lifecycle.track(action); }
    public void setStepLimit(long limit) { lifecycle.setStepLimit(limit); }
    final boolean tryStep() { return lifecycle.tryStep(); }
    final IllegalStateException stepLimitError() { return lifecycle.stepLimitError(); }
    final void discardContinuation() { lifecycle.discardContinuation(); }

    /** 防止上下文退出后继续调度动作。 */
    public static final class QuestExecutor implements Executor {
        private final QuestContinuationExecutor delegate;
        public QuestExecutor(AbstractQuestContext<?> context) { delegate = new QuestContinuationExecutor(context); }
        @Override public void execute(Runnable command) { delegate.execute(command); }
    }

    /** 通用的子帧跟踪、变量与清理行为。 */
    public abstract static class AbstractFrame implements Frame {
        protected final Frame parent;
        protected final List<Frame> frames;
        protected final VarTable varTable;
        protected final QuestContext questContext;
        protected CompletableFuture<?> future;
        protected final Deque<AutoCloseable> closeables = new ArrayDeque<>();

        protected AbstractFrame(Frame parent, List<Frame> frames, VarTable varTable, QuestContext context) {
            this.parent = parent;
            this.frames = frames;
            this.varTable = varTable;
            this.questContext = context;
        }

        @Override public QuestContext context() { return questContext; }
        @Override public List<Frame> children() { return Collections.unmodifiableList(frames); }
        @Override public Optional<Frame> parent() { return Optional.ofNullable(parent); }

        @Override public Frame newFrame(String name) { return FrameExecution.newFrame(this, name); }
        @Override public Frame newFrame(ParsedAction<?> action) { return FrameExecution.newFrame(this, action); }

        @Override public VarTable variables() { return varTable; }
        @Override public <C extends AutoCloseable> C addClosable(C closeable) {
            closeables.addFirst(closeable);
            return closeable;
        }

        @Override public void close() { FrameExecution.close(this); }
        @Override public boolean isDone() { return future == null || future.isDone(); }
        final void cleanup() { FrameExecution.cleanup(this); }
    }

    /** 按顺序执行具名任务块。 */
    public static final class SimpleNamedFrame extends AbstractFrame {
        private final String name;
        private final NamedFrameExecution execution;
        public SimpleNamedFrame(Frame parent, List<Frame> frames, VarTable vars, String name, QuestContext context) {
            super(parent, frames, vars, context);
            this.name = name;
            execution = new NamedFrameExecution(this, name);
        }
        @Override public String name() { return name; }
        @Override public Optional<ParsedAction<?>> currentAction() { return execution.currentAction(); }
        @Override public void setNext(ParsedAction<?> action) { execution.setNext(action); }
        @Override public void setNext(Quest.Block block) { execution.setNext(block); }
        @Override public <R> CompletableFuture<R> run() { return execution.run(); }
    }

    /** 在隔离的子帧中执行一个动作。 */
    public static final class SimpleActionFrame extends AbstractFrame {
        private final ParsedAction<?> action;

        public SimpleActionFrame(
                Frame parent, List<Frame> frames, VarTable vars, ParsedAction<?> action, QuestContext context) {
            super(parent, frames, vars, context);
            this.action = action;
        }

        @Override public String name() { return action.toString(); }
        @Override public Optional<ParsedAction<?>> currentAction() { return Optional.of(action); }
        @Override public void setNext(ParsedAction<?> next) { if (parent != null) parent.setNext(next); }
        @Override public void setNext(Quest.Block next) { if (parent != null) parent.setNext(next); }
        @Override public <R> CompletableFuture<R> run() { return FrameExecution.runSingle(this, action); }
    }

    /** 包括延迟动作变量在内的分层变量表。 */
    public static final class SimpleVarTable implements VarTable {
        private final VariableScope scope;
        public SimpleVarTable(Frame parent) { this(parent, new HashMap<>()); }
        public SimpleVarTable(Frame parent, Map<String, Object> values) { scope = new VariableScope(parent, values); }
        @Override public VarTable parent() { return scope.parent(); }
        @Override public <R> Optional<R> get(String name) { return scope.get(name); }
        @Override public <R> Optional<QuestFuture<R>> getFuture(String name) { return scope.getFuture(name); }
        @Override public void set(String name, Object value) { scope.set(name, value); }
        @Override public <R> void set(String name, ParsedAction<R> owner, CompletableFuture<R> actionFuture) {
            scope.set(name, owner, actionFuture);
        }
        @Override public void remove(String name) { scope.remove(name); }
        @Override public void clear() { scope.clear(); }
        @Override public Set<String> keys() { return scope.keys(); }
        @Override public Collection<Map.Entry<String, Object>> values() { return scope.values(); }
        @Override public void initialize(Frame frame) { scope.initialize(frame); }
        @Override public void close() { scope.close(); }
    }

}
