package me.kzheart.klib.script;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import me.kzheart.klib.script.TabooLibKetherInterop.OpenContainer;
import me.kzheart.klib.script.TabooLibKetherInterop.OpenResult;
import me.kzheart.klib.script.kether.core.ExitStatus;
import me.kzheart.klib.script.kether.core.ParsedAction;
import me.kzheart.klib.script.kether.core.Quest;
import me.kzheart.klib.script.kether.core.QuestAction;
import me.kzheart.klib.script.kether.core.QuestActionParser;
import me.kzheart.klib.script.kether.core.QuestContext;
import me.kzheart.klib.script.kether.core.QuestFuture;
import me.kzheart.klib.script.kether.core.QuestReader;
import me.kzheart.klib.script.kether.core.QuestService;

/** OpenContainer Kether 协议的纯 Java 反射代理。 */
final class TabooLibKetherProtocol {

    private TabooLibKetherProtocol() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> properties(Object source) {
        return (Map<String, Object>) source;
    }

    static final class RemoteActionParser implements QuestActionParser {
        private final OpenContainer remote;
        private final String consumerName;
        private final String action;
        private final String namespace;

        RemoteActionParser(
                OpenContainer remote,
                String consumerName,
                String action,
                String namespace
        ) {
            this.remote = remote;
            this.consumerName = consumerName;
            this.action = action;
            this.namespace = namespace;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> QuestAction<T> resolve(QuestReader reader) {
            OpenResult result = remote.call(
                    TabooLibKetherInterop.REMOTE_RESOLVE,
                    consumerName,
                    reader,
                    action,
                    namespace);
            if (!result.isSuccessful() || result.getValue() == null) {
                throw new IllegalStateException(
                        "Unable to create remote action " + namespace + ':' + action);
            }
            return (QuestAction<T>) new RemoteQuestAction<Object>(
                    remote, consumerName, result.getValue());
        }
    }

    static final class RemoteQuestReader implements QuestReader {
        private final OpenContainer remote;
        private final String consumerName;
        private final Object source;

        RemoteQuestReader(OpenContainer remote, String consumerName, Object source) {
            this.remote = remote;
            this.consumerName = consumerName;
            this.source = source;
        }

        @Override public char peek() { return (Character) invoke(source, "peek"); }
        @Override public char peek(int offset) { return (Character) invoke(source, "peek", offset); }
        @Override public int getIndex() { return ((Number) invoke(source, "getIndex")).intValue(); }
        @Override public void setIndex(int index) {
            try {
                invoke(source, "setIndex", index);
            } catch (IllegalStateException unsupported) {
                ReflectiveTabooLibContainers.setProperty(source, "index", index);
            }
        }
        @Override public int getMark() { return ((Number) invoke(source, "getMark")).intValue(); }
        @Override public boolean hasNext() { return (Boolean) invoke(source, "hasNext"); }
        @Override public String nextToken() { return String.valueOf(invoke(source, "nextToken")); }
        @Override public void mark() { invoke(source, "mark"); }
        @Override public void reset() { invoke(source, "reset"); }
        @Override public void expect(String value) { invoke(source, "expect", value); }

        @Override
        public boolean hasLineBreakBeforeNextToken() {
            try {
                return (Boolean) invoke(source, "hasLineBreakBeforeNextToken");
            } catch (IllegalStateException unsupported) {
                return false;
            }
        }

        @Override public <T> ParsedAction<T> nextAction() { return nextAction(null); }

        @Override
        @SuppressWarnings("unchecked")
        public <T> ParsedAction<T> nextAction(String namespace) {
            Object action = namespace == null
                    ? invoke(source, "nextAction")
                    : invoke(source, "nextAction", namespace);
            Object rawAction = ReflectiveTabooLibContainers.property(action, "action");
            Map<String, Object> rawProperties = properties(
                    ReflectiveTabooLibContainers.property(action, "properties"));
            return new ParsedAction<T>(
                    (QuestAction<T>) new RemoteQuestAction<Object>(
                            remote, consumerName, rawAction),
                    rawProperties);
        }
    }

    static final class RemoteQuestAction<T> extends QuestAction<T> {
        private final OpenContainer remote;
        private final String consumerName;
        private final Object source;

        RemoteQuestAction(OpenContainer remote, String consumerName, Object source) {
            this.remote = remote;
            this.consumerName = consumerName;
            this.source = source;
        }

        @Override
        @SuppressWarnings("unchecked")
        public CompletableFuture<T> process(QuestContext.Frame frame) {
            OpenResult result = remote.call(
                    TabooLibKetherInterop.CREATE_FRAME,
                    consumerName,
                    new LocalFrameSource(remote, consumerName, frame));
            if (!result.isSuccessful() || result.getValue() == null) {
                CompletableFuture<T> failed = new CompletableFuture<T>();
                failed.completeExceptionally(new IllegalStateException(
                        "Unable to create remote Kether frame in " + remote.name()));
                return failed;
            }
            Object future = invoke(source, "process", result.getValue());
            if (!(future instanceof CompletableFuture)) {
                throw new IllegalStateException("Remote Kether action did not return CompletableFuture");
            }
            return (CompletableFuture<T>) future;
        }
    }

    /**
     * 为 6.2.x 的跨容器 setExitStatus 传值错误提供形状容错，同时避免把本地帧实现细节
     * 直接暴露给远端反射器。
     */
    public static final class LocalFrameSource {
        private final OpenContainer remote;
        private final String consumerName;
        private final QuestContext.Frame delegate;

        LocalFrameSource(
                OpenContainer remote,
                String consumerName,
                QuestContext.Frame delegate
        ) {
            this.remote = remote;
            this.consumerName = consumerName;
            this.delegate = delegate;
        }

        public String name() { return delegate.name(); }
        public Object context() {
            return new LocalContextSource(remote, consumerName, delegate.context());
        }
        public Optional<ParsedAction<?>> currentAction() { return delegate.currentAction(); }
        public List<Object> children() {
            List<Object> result = new ArrayList<Object>();
            for (QuestContext.Frame child : delegate.children()) {
                result.add(new LocalFrameSource(remote, consumerName, child));
            }
            return result;
        }
        public Optional<Object> parent() {
            Optional<QuestContext.Frame> parent = delegate.parent();
            return parent.isPresent()
                    ? Optional.<Object>of(new LocalFrameSource(
                            remote, consumerName, parent.get()))
                    : Optional.empty();
        }
        public void setNext(Object value) {
            if (value instanceof ParsedAction) {
                delegate.setNext((ParsedAction<?>) value);
                return;
            }
            try {
                delegate.setNext(fromForeignParsed(remote, consumerName, value));
            } catch (RuntimeException notAction) {
                Object label = ReflectiveTabooLibContainers.property(value, "label");
                Optional<Quest.Block> block = delegate.context().getQuest()
                        .getBlock(String.valueOf(label));
                if (!block.isPresent()) {
                    throw notAction;
                }
                delegate.setNext(block.get());
            }
        }
        public Object newFrame(String name) {
            return new LocalFrameSource(remote, consumerName, delegate.newFrame(name));
        }
        public Object newFrame(Object action) {
            ParsedAction<?> parsed = action instanceof ParsedAction
                    ? (ParsedAction<?>) action
                    : fromForeignParsed(remote, consumerName, action);
            return new LocalFrameSource(remote, consumerName, delegate.newFrame(parsed));
        }
        public Object variables() { return delegate.variables(); }
        public <C extends AutoCloseable> C addClosable(C closeable) {
            return delegate.addClosable(closeable);
        }
        public <T> CompletableFuture<T> run() { return delegate.run(); }
        public void close() { delegate.close(); }
        public boolean isDone() { return delegate.isDone(); }
    }

    public static final class LocalContextSource {
        private final OpenContainer remote;
        private final String consumerName;
        private final QuestContext delegate;

        LocalContextSource(
                OpenContainer remote,
                String consumerName,
                QuestContext delegate
        ) {
            this.remote = remote;
            this.consumerName = consumerName;
            this.delegate = delegate;
        }

        public Object getQuest() { return delegate.getQuest(); }
        public Optional<ExitStatus> getExitStatus() { return delegate.getExitStatus(); }
        public CompletableFuture<Object> runActions() { return delegate.runActions(); }
        public Executor getExecutor() { return delegate.getExecutor(); }
        public void terminate() { delegate.terminate(); }
        public Object rootFrame() {
            return new LocalFrameSource(remote, consumerName, delegate.rootFrame());
        }

        public void setExitStatus(Object foreign) {
            Object value = foreign;
            try {
                Object successful = ReflectiveTabooLibContainers.property(foreign, "successful");
                if (Boolean.TRUE.equals(successful)) {
                    value = ReflectiveTabooLibContainers.property(foreign, "value");
                }
            } catch (IllegalStateException notOpenResult) {
                // 6.3.x 直接传 ExitStatus。
            }
            if (value instanceof ExitStatus) {
                delegate.setExitStatus((ExitStatus) value);
                return;
            }
            delegate.setExitStatus(new ExitStatus(
                    (Boolean) ReflectiveTabooLibContainers.property(value, "running"),
                    (Boolean) ReflectiveTabooLibContainers.property(value, "waiting"),
                    ((Number) ReflectiveTabooLibContainers.property(
                            value, "startTime")).longValue()));
        }
    }

    static final class RemoteFrame implements QuestContext.Frame {
        private final OpenContainer remote;
        private final String consumerName;
        private final Object source;

        RemoteFrame(OpenContainer remote, String consumerName, Object source) {
            this.remote = remote;
            this.consumerName = consumerName;
            this.source = source;
        }

        @Override public String name() { return String.valueOf(invoke(source, "name")); }
        @Override public QuestContext context() {
            return new RemoteContext(remote, consumerName, invoke(source, "context"));
        }

        @Override
        public Optional<ParsedAction<?>> currentAction() {
            Object optional = invoke(source, "currentAction");
            if (!(optional instanceof Optional) || !((Optional<?>) optional).isPresent()) {
                return Optional.empty();
            }
            return Optional.<ParsedAction<?>>of(fromForeignParsed(
                    remote, consumerName, ((Optional<?>) optional).get()));
        }

        @Override
        public List<QuestContext.Frame> children() {
            List<?> children = (List<?>) invoke(source, "children");
            List<QuestContext.Frame> result = new ArrayList<QuestContext.Frame>();
            for (Object child : children) {
                result.add(new RemoteFrame(remote, consumerName, child));
            }
            return result;
        }

        @Override
        public Optional<QuestContext.Frame> parent() {
            Object optional = invoke(source, "parent");
            if (!(optional instanceof Optional) || !((Optional<?>) optional).isPresent()) {
                return Optional.empty();
            }
            return Optional.<QuestContext.Frame>of(new RemoteFrame(
                    remote, consumerName, ((Optional<?>) optional).get()));
        }

        @Override public void setNext(ParsedAction<?> action) {
            invoke(source, "setNext", toForeignParsed(remote, consumerName, action));
        }

        @Override public void setNext(Quest.Block block) {
            if (!(block instanceof RemoteBlock)) {
                throw new IllegalArgumentException("Kether block belongs to a different runtime");
            }
            invoke(source, "setNext", ((RemoteBlock) block).source);
        }

        @Override public QuestContext.Frame newFrame(String name) {
            return new RemoteFrame(remote, consumerName, invoke(source, "newFrame", name));
        }

        @Override public QuestContext.Frame newFrame(ParsedAction<?> action) {
            return new RemoteFrame(remote, consumerName,
                    invoke(source, "newFrame", toForeignParsed(remote, consumerName, action)));
        }

        @Override public QuestContext.VarTable variables() {
            return new RemoteVarTable(remote, consumerName, invoke(source, "variables"));
        }

        @Override @SuppressWarnings("unchecked")
        public <C extends AutoCloseable> C addClosable(C closeable) {
            return (C) invoke(source, "addClosable", closeable);
        }

        @Override @SuppressWarnings("unchecked")
        public <T> CompletableFuture<T> run() {
            return (CompletableFuture<T>) invoke(source, "run");
        }

        @Override public void close() { invoke(source, "close"); }
        @Override public boolean isDone() { return (Boolean) invoke(source, "isDone"); }
    }

    private static final class RemoteContext implements QuestContext {
        private final OpenContainer remote;
        private final String consumerName;
        private final Object source;

        private RemoteContext(OpenContainer remote, String consumerName, Object source) {
            this.remote = remote;
            this.consumerName = consumerName;
            this.source = source;
        }

        @Override public QuestService<? extends QuestContext> getService() {
            throw new UnsupportedOperationException("remote Kether context has no local service");
        }
        @Override public Quest getQuest() {
            return new RemoteQuest(remote, consumerName, invoke(source, "getQuest"));
        }
        @Override public void setExitStatus(ExitStatus status) {
            OpenResult result = remote.call(
                    TabooLibKetherInterop.CREATE_EXIT_STATUS,
                    status.isRunning(), status.isWaiting(), status.getStartTime());
            if (!result.isSuccessful()) {
                throw new IllegalStateException("Unable to create remote exit status");
            }
            invoke(source, "setExitStatus", result.getValue());
        }
        @Override public Optional<ExitStatus> getExitStatus() {
            Object optional = invoke(source, "getExitStatus");
            if (!(optional instanceof Optional) || !((Optional<?>) optional).isPresent()) {
                return Optional.empty();
            }
            Object status = ((Optional<?>) optional).get();
            return Optional.of(new ExitStatus(
                    (Boolean) ReflectiveTabooLibContainers.property(status, "running"),
                    (Boolean) ReflectiveTabooLibContainers.property(status, "waiting"),
                    ((Number) ReflectiveTabooLibContainers.property(status, "startTime")).longValue()));
        }
        @Override @SuppressWarnings("unchecked") public CompletableFuture<Object> runActions() {
            return (CompletableFuture<Object>) invoke(source, "runActions");
        }
        @Override public Executor getExecutor() {
            Object executor = invoke(source, "getExecutor");
            return executor instanceof Executor
                    ? (Executor) executor
                    : command -> invoke(executor, "execute", command);
        }
        @Override public void terminate() { invoke(source, "terminate"); }
        @Override public QuestContext.Frame rootFrame() {
            return new RemoteFrame(remote, consumerName, invoke(source, "rootFrame"));
        }
    }

    private static final class RemoteVarTable implements QuestContext.VarTable {
        private final OpenContainer remote;
        private final String consumerName;
        private final Object source;

        private RemoteVarTable(OpenContainer remote, String consumerName, Object source) {
            this.remote = remote;
            this.consumerName = consumerName;
            this.source = source;
        }

        @Override @SuppressWarnings("unchecked") public <T> Optional<T> get(String name) {
            Object value = invoke(source, "get", name);
            return value instanceof Optional ? (Optional<T>) value : Optional.<T>empty();
        }
        @Override @SuppressWarnings("unchecked") public <T> Optional<QuestFuture<T>> getFuture(String name) {
            Object value = invoke(source, "getFuture", name);
            if (!(value instanceof Optional) || !((Optional<?>) value).isPresent()) {
                return Optional.empty();
            }
            Object foreign = ((Optional<?>) value).get();
            if (foreign instanceof QuestFuture) {
                return Optional.of((QuestFuture<T>) foreign);
            }
            Object action = ReflectiveTabooLibContainers.property(foreign, "action");
            Object future = ReflectiveTabooLibContainers.property(foreign, "future");
            @SuppressWarnings("unchecked") ParsedAction<T> parsed = (ParsedAction<T>)
                    fromForeignParsed(remote, consumerName, action);
            return Optional.of(new QuestFuture<T>(
                    parsed,
                    future instanceof CompletableFuture
                            ? (CompletableFuture<T>) future
                            : null));
        }
        @Override public void set(String name, Object value) { invoke(source, "set", name, value); }
        @Override public void remove(String name) { invoke(source, "remove", name); }
        @Override public void clear() { invoke(source, "clear"); }
        @Override public <T> void set(String name, ParsedAction<T> owner,
                CompletableFuture<T> future) {
            invoke(source, "set", name, toForeignParsed(remote, consumerName, owner), future);
        }
        @Override @SuppressWarnings("unchecked") public Set<String> keys() {
            return (Set<String>) invoke(source, "keys");
        }
        @Override @SuppressWarnings("unchecked") public Collection<Map.Entry<String, Object>> values() {
            return (Collection<Map.Entry<String, Object>>) invoke(source, "values");
        }
        @Override public void initialize(QuestContext.Frame frame) {
            OpenResult result = remote.call(
                    TabooLibKetherInterop.CREATE_FRAME, consumerName, frame);
            invoke(source, "initialize", result.getValue());
        }
        @Override public void close() { invoke(source, "close"); }
        @Override public QuestContext.VarTable parent() {
            Object value = invoke(source, "parent");
            return value == null ? null : new RemoteVarTable(remote, consumerName, value);
        }
    }

    private static final class RemoteQuest implements Quest {
        private final OpenContainer remote;
        private final String consumerName;
        private final Object source;

        private RemoteQuest(OpenContainer remote, String consumerName, Object source) {
            this.remote = remote;
            this.consumerName = consumerName;
            this.source = source;
        }
        @Override public String getId() { return String.valueOf(invoke(source, "getId")); }
        @Override public Optional<Quest.Block> getBlock(String label) {
            Object optional = invoke(source, "getBlock", label);
            if (!(optional instanceof Optional) || !((Optional<?>) optional).isPresent()) {
                return Optional.empty();
            }
            return Optional.<Quest.Block>of(new RemoteBlock(
                    remote, consumerName, ((Optional<?>) optional).get()));
        }
        @Override public Map<String, Quest.Block> getBlocks() {
            @SuppressWarnings("unchecked") Map<String, Object> blocks =
                    (Map<String, Object>) invoke(source, "getBlocks");
            Map<String, Quest.Block> result = new java.util.LinkedHashMap<String, Quest.Block>();
            for (Map.Entry<String, Object> entry : blocks.entrySet()) {
                result.put(entry.getKey(), new RemoteBlock(remote, consumerName, entry.getValue()));
            }
            return result;
        }
        @Override public Optional<Quest.Block> blockOf(ParsedAction<?> action) {
            Object optional = invoke(source, "blockOf",
                    toForeignParsed(remote, consumerName, action));
            if (!(optional instanceof Optional) || !((Optional<?>) optional).isPresent()) {
                return Optional.empty();
            }
            return Optional.<Quest.Block>of(new RemoteBlock(
                    remote, consumerName, ((Optional<?>) optional).get()));
        }
    }

    private static final class RemoteBlock implements Quest.Block {
        private final OpenContainer remote;
        private final String consumerName;
        private final Object source;

        private RemoteBlock(OpenContainer remote, String consumerName, Object source) {
            this.remote = remote;
            this.consumerName = consumerName;
            this.source = source;
        }
        @Override public String getLabel() { return String.valueOf(invoke(source, "getLabel")); }
        @Override public List<ParsedAction<?>> getActions() {
            List<?> actions = (List<?>) invoke(source, "getActions");
            List<ParsedAction<?>> result = new ArrayList<ParsedAction<?>>();
            for (Object action : actions) {
                result.add(fromForeignParsed(remote, consumerName, action));
            }
            return result;
        }
        @Override public int indexOf(ParsedAction<?> action) {
            return ((Number) invoke(source, "indexOf",
                    toForeignParsed(remote, consumerName, action))).intValue();
        }
        @Override public Optional<ParsedAction<?>> get(int index) {
            Object optional = invoke(source, "get", index);
            if (!(optional instanceof Optional) || !((Optional<?>) optional).isPresent()) {
                return Optional.empty();
            }
            return Optional.<ParsedAction<?>>of(fromForeignParsed(
                    remote, consumerName, ((Optional<?>) optional).get()));
        }
    }

    private static ParsedAction<?> fromForeignParsed(
            OpenContainer remote,
            String consumerName,
            Object source
    ) {
        Object action = ReflectiveTabooLibContainers.property(source, "action");
        Object rawProperties = ReflectiveTabooLibContainers.property(source, "properties");
        return new ParsedAction<Object>(
                new RemoteQuestAction<Object>(remote, consumerName, action),
                properties(rawProperties));
    }

    private static Object toForeignParsed(
            OpenContainer remote,
            String consumerName,
            ParsedAction<?> action
    ) {
        OpenResult result = remote.call(
                TabooLibKetherInterop.CREATE_PARSED_ACTION,
                consumerName,
                action.getAction(),
                action.getProperties());
        if (!result.isSuccessful() || result.getValue() == null) {
            throw new IllegalStateException("Unable to create remote ParsedAction in " + remote.name());
        }
        return result.getValue();
    }

    private static Object invoke(Object target, String name, Object... arguments) {
        return ReflectiveTabooLibContainers.invoke(target, name, arguments);
    }
}
