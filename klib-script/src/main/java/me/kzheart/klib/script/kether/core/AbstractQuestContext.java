/*
 * 派生自 TabooLib 的 taboolib.library.kether.AbstractQuestContext。
 * Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6
 */
package me.kzheart.klib.script.kether.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/** 兼容上游且不依赖 Guava 或 Kotlin 的帧执行器。 */
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
    private final AtomicLong steps = new AtomicLong();
    private volatile long stepLimit = DEFAULT_STEP_LIMIT;

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

    @Override
    public CompletableFuture<Object> runActions() {
        checkState(future == null, "already running");
        future = rootFrame.run();
        future.thenRun(() -> {
            if (exitStatus == null) exitStatus = ExitStatus.success();
        });
        return future;
    }

    @Override
    public void terminate() {
        CompletableFuture<Object> running = future;
        if (running != null && !running.isDone()) running.completeExceptionally(new QuestCloseException());
        rootFrame.close();
        future = null;
    }

    /** 覆盖此上下文的 {@link #DEFAULT_STEP_LIMIT 执行步数限制}。 */
    public void setStepLimit(long stepLimit) {
        if (stepLimit <= 0) throw new IllegalArgumentException("stepLimit must be positive");
        this.stepLimit = stepLimit;
    }

    final boolean tryStep() {
        return steps.incrementAndGet() <= stepLimit;
    }

    final IllegalStateException stepLimitError() {
        return new IllegalStateException("Quest '" + quest.getId() + "' exceeded the execution step limit of "
                + stepLimit + " actions; the script likely loops forever");
    }

    final void discardContinuation() {
        // 上下文已经退出；使根 Future 失败，确保终止、写回和关闭链仍会运行，
        // 而不是静默丢弃续接。
        CompletableFuture<Object> running = future;
        if (running != null && !running.isDone()) running.completeExceptionally(new QuestCloseException());
    }

    private static void checkState(boolean state, String message) {
        if (!state) throw new IllegalStateException(message);
    }

    private static boolean tryStep(QuestContext context) {
        return !(context instanceof AbstractQuestContext) || ((AbstractQuestContext<?>) context).tryStep();
    }

    private static IllegalStateException stepLimitError(QuestContext context) {
        return context instanceof AbstractQuestContext
                ? ((AbstractQuestContext<?>) context).stepLimitError()
                : new IllegalStateException("Quest exceeded its execution step limit");
    }

    /** 防止上下文退出后继续调度动作。 */
    public static final class QuestExecutor implements Executor {
        private final AbstractQuestContext<?> questContext;
        private volatile Executor actual;

        public QuestExecutor(AbstractQuestContext<?> questContext) {
            this.questContext = questContext;
        }

        @Override
        public void execute(Runnable command) {
            if (questContext.getExitStatus().isPresent()) {
                questContext.discardContinuation();
                return;
            }
            Executor resolved = actual;
            if (resolved == null) {
                resolved = questContext.createExecutor();
                actual = resolved;
            }
            resolved.execute(command);
        }
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

        @Override
        public Frame newFrame(String name) {
            Frame frame = new SimpleNamedFrame(this, new LinkedList<>(), new SimpleVarTable(this), name, context());
            frames.add(frame);
            return frame;
        }

        @Override
        public Frame newFrame(ParsedAction<?> action) {
            Frame frame;
            if (action.get(ActionProperties.REQUIRE_FRAME, false)) {
                frame = new SimpleNamedFrame(this, new LinkedList<>(), new SimpleVarTable(this),
                        "__anon__" + System.nanoTime(), context());
                frame.setNext(action);
            } else {
                frame = new SimpleActionFrame(this, new LinkedList<>(), new SimpleVarTable(this), action, context());
            }
            frames.add(frame);
            return frame;
        }

        @Override public VarTable variables() { return varTable; }
        @Override public <C extends AutoCloseable> C addClosable(C closeable) {
            closeables.addFirst(closeable);
            return closeable;
        }

        @Override
        public void close() {
            for (Frame frame : new ArrayList<>(frames)) frame.close();
            varTable.close();
            cleanup();
            if (future != null && !future.isDone()) future.cancel(false);
            future = null;
        }

        @Override public boolean isDone() { return future == null || future.isDone(); }

        final void cleanup() {
            while (!closeables.isEmpty()) {
                try {
                    closeables.removeFirst().close();
                } catch (Exception ignored) {
                    // 清理采用尽力而为策略，且不得替换动作结果。
                }
            }
        }
    }

    /** 按顺序执行具名任务块。 */
    public static final class SimpleNamedFrame extends AbstractFrame {
        private final String name;
        private Quest.Block block;
        private int nextIndex = -1;
        private int currentIndex = -1;

        public SimpleNamedFrame(
                Frame parent, List<Frame> frames, VarTable vars, String name, QuestContext context) {
            super(parent, frames, vars, context);
            this.name = name;
            context.getQuest().getBlock(name).ifPresent(this::setNext);
        }

        @Override public String name() { return name; }
        @Override public Optional<ParsedAction<?>> currentAction() {
            return block == null || currentIndex < 0 ? Optional.empty() : block.get(currentIndex);
        }

        @Override
        public void setNext(ParsedAction<?> action) {
            Quest.Block owner = context().getQuest().blockOf(action)
                    .orElseThrow(() -> new IllegalArgumentException(action + " is not in quest"));
            block = owner;
            nextIndex = owner.indexOf(action);
        }

        @Override public void setNext(Quest.Block block) {
            this.block = block;
            this.nextIndex = 0;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> CompletableFuture<R> run() {
            checkState(future == null, "already running");
            varTable.initialize(this);
            CompletableFuture<R> result = new CompletableFuture<>();
            future = result;
            process(null, result);
            return result;
        }

        private <R> void process(Object lastValue, CompletableFuture<R> result) {
            Object value = lastValue;
            while (!context().getExitStatus().isPresent()) {
                if (!tryStep(context())) {
                    result.completeExceptionally(stepLimitError(context()));
                    return;
                }
                cleanup();
                frames.removeIf(Frame::isDone);
                Optional<ParsedAction<?>> next = nextAction();
                if (!next.isPresent()) {
                    @SuppressWarnings("unchecked") R cast = (R) value;
                    result.complete(cast);
                    return;
                }
                CompletableFuture<?> actionFuture;
                try {
                    actionFuture = next.get().process(this);
                } catch (RuntimeException exception) {
                    result.completeExceptionally(exception);
                    return;
                }
                if (actionFuture.isDone()) {
                    try {
                        value = actionFuture.join();
                    } catch (CompletionException exception) {
                        result.completeExceptionally(exception.getCause());
                        return;
                    }
                } else {
                    CompletableFuture<?> continuation = actionFuture.whenCompleteAsync((completed, error) -> {
                        if (error != null) result.completeExceptionally(unwrap(error));
                        else process(completed, result);
                    }, context().getExecutor());
                    continuation.whenComplete((ignored, schedulingFailure) -> {
                        if (schedulingFailure != null && !result.isDone()) {
                            result.completeExceptionally(unwrap(schedulingFailure));
                        }
                    });
                    return;
                }
            }
            @SuppressWarnings("unchecked") R cast = (R) value;
            result.complete(cast);
        }

        private Optional<ParsedAction<?>> nextAction() {
            if (block == null || nextIndex < 0) return Optional.empty();
            currentIndex = nextIndex++;
            return block.get(currentIndex);
        }
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
        @Override @SuppressWarnings("unchecked") public <R> CompletableFuture<R> run() {
            checkState(future == null, "already running");
            varTable.initialize(this);
            future = action.process(this);
            return (CompletableFuture<R>) future;
        }
    }

    /** 包括延迟动作变量在内的分层变量表。 */
    public static final class SimpleVarTable implements VarTable {
        private final Frame parent;
        private final Map<String, Object> values;

        public SimpleVarTable(Frame parent) { this(parent, new HashMap<>()); }
        public SimpleVarTable(Frame parent, Map<String, Object> values) {
            this.parent = parent;
            this.values = values;
        }

        @Override public VarTable parent() { return parent == null ? null : parent.variables(); }
        @Override @SuppressWarnings("unchecked") public <R> Optional<R> get(String name) {
            Object value = values.get(name);
            if (value == null && parent != null) return parent.variables().get(name);
            if (value instanceof QuestFuture) {
                CompletableFuture<?> deferred = ((QuestFuture<?>) value).getFuture();
                if (deferred == null || !deferred.isDone()) {
                    throw new IllegalStateException(
                            "deferred variable is not ready: " + name
                                    + "; use getFuture() for asynchronous access");
                }
                value = deferred.join();
            }
            return (Optional<R>) Optional.ofNullable(value);
        }
        @Override @SuppressWarnings("unchecked") public <R> Optional<QuestFuture<R>> getFuture(String name) {
            Object value = values.get(name);
            if (value == null && parent != null) return parent.variables().getFuture(name);
            return value instanceof QuestFuture ? Optional.of((QuestFuture<R>) value) : Optional.empty();
        }
        @Override public void set(String name, Object value) {
            if (name.startsWith("~") || parent() == null) values.put(name, value); else parent().set(name, value);
        }
        @Override public <R> void set(String name, ParsedAction<R> owner, CompletableFuture<R> actionFuture) {
            values.put(name, new QuestFuture<>(owner, actionFuture));
        }
        @Override public void remove(String name) { values.remove(name); }
        @Override public void clear() { values.clear(); }
        @Override public Set<String> keys() { return Collections.unmodifiableSet(values.keySet()); }
        @Override public Collection<Map.Entry<String, Object>> values() {
            return Collections.unmodifiableCollection(values.entrySet());
        }
        @Override public void initialize(Frame frame) {
            for (Object value : values.values()) if (value instanceof QuestFuture) ((QuestFuture<?>) value).run(frame);
        }
        @Override public void close() {
            for (Object value : values.values()) {
                if (value instanceof QuestFuture && ((QuestFuture<?>) value).getFuture() != null) {
                    ((QuestFuture<?>) value).close();
                }
            }
        }
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }
}
