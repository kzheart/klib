package me.kzheart.klib.script;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.kzheart.klib.script.kether.core.ParsedAction;
import me.kzheart.klib.script.kether.core.ActionProperties;
import me.kzheart.klib.script.kether.core.Quest;
import me.kzheart.klib.script.kether.core.QuestAction;
import me.kzheart.klib.script.kether.core.QuestContext;
import me.kzheart.klib.script.kether.core.QuestReader;
import me.kzheart.klib.script.kether.core.SimpleQuestContext;
import me.kzheart.klib.script.kether.core.SimpleQuestService;

/** 将公开引擎契约连接到引入的 Java Kether 加载器和帧运行时。 */
final class CoreScriptRuntime {

    private static final String CONTEXT_VARIABLE = "~klib:context";
    private static final String EVALUATION_STATE_VARIABLE = "~klib:evaluation-state";
    private static final Pattern ACTION_START = Pattern.compile(
            "(?m)(?:^|[\\n;{])\\s*([A-Za-z_][A-Za-z0-9_:.-]*)");
    private static final AtomicLong SCRIPT_IDS = new AtomicLong();
    private static final int MAX_NESTED_EVALUATIONS = 64;
    private static final int MAX_SOURCE_CHARS = 256 * 1024;
    private static final int MAX_ACTIONS = 10_000;
    private static final int MAX_SYNTAX_DEPTH = 128;
    private static final int MAX_CACHE_ENTRIES = 128;
    private static final int MAX_CACHE_SOURCE_CHARS = 1024 * 1024;
    private static final int MAX_CACHEABLE_SOURCE_CHARS = 64 * 1024;

    private final StatementRegistry registry;
    private final UnknownStatementResolver unknownResolver;
    private final Executor continuationExecutor;
    private final Object cacheLock = new Object();
    private final LinkedHashMap<CacheKey, CacheEntry> compiledScripts =
            new LinkedHashMap<CacheKey, CacheEntry>(16, 0.75F, true);
    private final AtomicLong compilationCount = new AtomicLong();
    private long cacheRegistryVersion = -1L;
    private int cachedSourceChars;

    CoreScriptRuntime(
            StatementRegistry registry,
            UnknownStatementResolver unknownResolver,
            Executor continuationExecutor
    ) {
        this.registry = registry;
        this.unknownResolver = unknownResolver;
        this.continuationExecutor = continuationExecutor;
    }

    CompletionStage<Object> eval(String source, ScriptContext scriptContext) {
        return eval(source, scriptContext, new EvaluationState());
    }

    private CompletionStage<Object> eval(
            String source,
            ScriptContext scriptContext,
            EvaluationState evaluationState
    ) {
        final Quest quest;
        try {
            quest = compiledQuest(source, scriptContext.namespaces());
        } catch (RuntimeException failure) {
            return failed(failure);
        } catch (StackOverflowError failure) {
            return failed(compilationFailure(
                    "syntax nesting exhausted the parser stack", failure));
        }
        final SimpleQuestService service = new SimpleQuestService(continuationExecutor);
        try {
            SimpleQuestContext context = service.newContext(quest);
            for (Map.Entry<String, Object> variable : scriptContext.variables().entrySet()) {
                context.rootFrame().variables().set(variable.getKey(), variable.getValue());
            }
            context.rootFrame().variables().set(CONTEXT_VARIABLE, scriptContext);
            context.rootFrame().variables().set(EVALUATION_STATE_VARIABLE, evaluationState);
            CompletableFuture<Object> result = new CompletableFuture<Object>();
            context.runActions().whenComplete((value, failure) -> {
                service.close();
                if (failure == null) {
                    result.complete(value);
                } else {
                    result.completeExceptionally(failure);
                }
            });
            return result;
        } catch (RuntimeException failure) {
            service.close();
            CompletableFuture<Object> failed = new CompletableFuture<Object>();
            failed.completeExceptionally(failure);
            return failed;
        } catch (StackOverflowError failure) {
            service.close();
            return failed(actionFailure(
                    failure,
                    scriptContext,
                    new StatementCall("kether", Collections.<String>emptyList(), 1, 1,
                            evaluationState::evalNested)));
        }
    }

    private void installStatements(
            SimpleQuestService service,
            List<String> namespaces,
            List<StatementRegistry.EntryView> registeredEntries,
            String source,
            int lineOffset
    ) {
        for (StatementRegistry.EntryView entry : registeredEntries) {
            if (entry.ketherParser != null) {
                service.getRegistry().registerAction(
                        entry.namespace, entry.name, entry.ketherParser);
            } else {
                service.getRegistry().registerAction(
                        entry.namespace,
                        entry.name,
                        upperParser(
                                entry.namespace + ':' + entry.name,
                                entry.name,
                                namespaces,
                                lineOffset));
            }
        }
        if (unknownResolver instanceof KetherParserResolver) {
            service.getRegistry().setFallbackParser((name, selectedNamespaces) ->
                    ((KetherParserResolver) unknownResolver).parser(
                            name, selectedNamespaces));
        } else if (unknownResolver != null) {
            Matcher matcher = ACTION_START.matcher(source);
            while (matcher.find()) {
                String name = matcher.group(1).toLowerCase(Locale.ROOT);
                if ("def".equals(name)) {
                    continue;
                }
                if (!containsEntry(registeredEntries, name, namespaces)) {
                    me.kzheart.klib.script.kether.core.QuestActionParser parser =
                            unknownResolver instanceof KetherParserResolver
                                    ? ((KetherParserResolver) unknownResolver).parser(name, namespaces)
                                    : unknownParser(name);
                    service.getRegistry().registerAction("kether", name, parser);
                }
            }
        }
    }

    private static boolean containsEntry(
            List<StatementRegistry.EntryView> entries,
            String name,
            List<String> namespaces
    ) {
        int separator = name.indexOf(':');
        if (separator > 0) {
            String namespace = name.substring(0, separator);
            String localName = name.substring(separator + 1);
            for (StatementRegistry.EntryView entry : entries) {
                if (entry.namespace.equals(namespace) && entry.name.equals(localName)) {
                    return true;
                }
            }
            return false;
        }
        for (String namespace : namespaces) {
            for (StatementRegistry.EntryView entry : entries) {
                if (entry.namespace.equals(namespace) && entry.name.equals(name)) {
                    return true;
                }
            }
        }
        for (StatementRegistry.EntryView entry : entries) {
            if (entry.namespace.equals("global") && entry.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private Quest compiledQuest(String source, List<String> namespaces) {
        validateCompilationBudget(source);
        String normalized = normalizeSource(source);
        StatementRegistry.Snapshot snapshot = registry.snapshot();
        CacheKey key = new CacheKey(normalized, namespaces);
        Quest cached = cached(snapshot.version(), key);
        if (cached != null) {
            return cached;
        }

        SimpleQuestService compiler = new SimpleQuestService();
        Quest compiled;
        try {
            int lineOffset = source.trim().startsWith("def ") ? 0 : 1;
            installStatements(
                    compiler,
                    key.namespaces,
                    snapshot.entries(),
                    source,
                    lineOffset);
            compiled = compiler.load(
                    "klib-" + SCRIPT_IDS.incrementAndGet(),
                    normalized,
                    key.namespaces);
            int actions = 0;
            for (Quest.Block block : compiled.getBlocks().values()) {
                actions += block.getActions().size();
                if (actions > MAX_ACTIONS) {
                    throw compilationFailure(
                            "action count exceeds " + MAX_ACTIONS, null);
                }
            }
            compilationCount.incrementAndGet();
        } finally {
            compiler.close();
        }
        cache(snapshot.version(), key, compiled);
        return compiled;
    }

    private Quest cached(long registryVersion, CacheKey key) {
        if (key.source.length() > MAX_CACHEABLE_SOURCE_CHARS) {
            return null;
        }
        synchronized (cacheLock) {
            if (registryVersion < cacheRegistryVersion) {
                return null;
            }
            if (registryVersion != cacheRegistryVersion) {
                clearCache(registryVersion);
            }
            CacheEntry entry = compiledScripts.get(key);
            return entry == null ? null : entry.quest;
        }
    }

    private void cache(long registryVersion, CacheKey key, Quest quest) {
        if (key.source.length() > MAX_CACHEABLE_SOURCE_CHARS
                || registry.version() != registryVersion) {
            return;
        }
        synchronized (cacheLock) {
            if (registryVersion < cacheRegistryVersion) {
                return;
            }
            if (registryVersion != cacheRegistryVersion) {
                clearCache(registryVersion);
            }
            CacheEntry previous = compiledScripts.put(
                    key, new CacheEntry(quest, key.source.length()));
            if (previous != null) {
                cachedSourceChars -= previous.sourceChars;
            }
            cachedSourceChars += key.source.length();
            Iterator<Map.Entry<CacheKey, CacheEntry>> iterator =
                    compiledScripts.entrySet().iterator();
            while ((compiledScripts.size() > MAX_CACHE_ENTRIES
                    || cachedSourceChars > MAX_CACHE_SOURCE_CHARS)
                    && iterator.hasNext()) {
                Map.Entry<CacheKey, CacheEntry> eldest = iterator.next();
                cachedSourceChars -= eldest.getValue().sourceChars;
                iterator.remove();
            }
        }
    }

    private void clearCache(long registryVersion) {
        compiledScripts.clear();
        cachedSourceChars = 0;
        cacheRegistryVersion = registryVersion;
    }

    int cachedScriptCount() {
        synchronized (cacheLock) {
            return compiledScripts.size();
        }
    }

    long compilationCount() {
        return compilationCount.get();
    }

    private me.kzheart.klib.script.kether.core.QuestActionParser upperParser(
            final String lookupName,
            final String name,
            final List<String> namespaces,
            final int lineOffset
    ) {
        if ("if".equals(name)) {
            return conditionalParser();
        }
        if ("namespace".equals(name)) {
            return namespaceParser();
        }
        return me.kzheart.klib.script.kether.core.QuestActionParser.of(reader -> {
            List<String> arguments = readArguments(name, reader);
            return new QuestAction<Object>() {
                @Override
                public CompletableFuture<Object> process(QuestContext.Frame frame) {
                    ScriptContext context = context(frame);
                    Map<String, Object> variablesBefore = context.variables();
                    SourcePosition position = sourcePosition(frame, lineOffset);
                    Optional<QuestActionParser> parser = registry.resolve(lookupName, namespaces);
                    if (!parser.isPresent()) {
                        return failed(new IllegalArgumentException("Unknown statement: " + name));
                    }
                    StatementCall call = new StatementCall(
                            name,
                            arguments,
                            position.line,
                            position.column,
                            evaluationState(frame)::evalNested);
                    CompletionStage<Object> execution;
                    try {
                        execution = parser.get().execute(call, context);
                    } catch (RuntimeException failure) {
                        return failed(actionFailure(failure, context, call));
                    } catch (StackOverflowError failure) {
                        return failed(actionFailure(failure, context, call));
                    }
                    if (execution == null) {
                        return failed(actionFailure(
                                new IllegalStateException("statement returned null stage"),
                                context,
                                call));
                    }
                    CompletableFuture<Object> result = new CompletableFuture<Object>();
                    execution.whenComplete((value, failure) -> {
                        if (failure == null) {
                            try {
                                synchronizeFrameVariables(
                                        frame.context().rootFrame(),
                                        variablesBefore,
                                        context.variables());
                                result.complete(value);
                            } catch (RuntimeException syncFailure) {
                                result.completeExceptionally(actionFailure(
                                        syncFailure, context, call));
                            } catch (StackOverflowError syncFailure) {
                                result.completeExceptionally(actionFailure(
                                        syncFailure, context, call));
                            }
                        } else {
                            result.completeExceptionally(actionFailure(
                                    unwrap(failure), context, call));
                        }
                    });
                    return result;
                }
            };
        });
    }

    private static void synchronizeFrameVariables(
            QuestContext.Frame rootFrame,
            Map<String, Object> before,
            Map<String, Object> after
    ) {
        for (Map.Entry<String, Object> variable : after.entrySet()) {
            Object previous = before.get(variable.getKey());
            if (!before.containsKey(variable.getKey())
                    || !Objects.equals(previous, variable.getValue())) {
                rootFrame.variables().set(variable.getKey(), variable.getValue());
            }
        }
        for (String name : before.keySet()) {
            if (!after.containsKey(name)) {
                rootFrame.variables().remove(name);
            }
        }
    }

    private static SourcePosition sourcePosition(QuestContext.Frame frame, int lineOffset) {
        ParsedAction<?> action = frame.currentAction().orElse(null);
        if (action == null) {
            return new SourcePosition(1, 1);
        }
        return new SourcePosition(
                Math.max(1, action.get(ActionProperties.LINE, Integer.valueOf(1)).intValue()
                        - lineOffset),
                action.get(ActionProperties.COLUMN, Integer.valueOf(1)).intValue());
    }

    private static ScriptException actionFailure(
            Throwable failure,
            ScriptContext context,
            StatementCall call
    ) {
        if (failure instanceof ScriptException) {
            return (ScriptException) failure;
        }
        String detail = failure.getMessage() == null
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
        return new ScriptException(
                "action-failed",
                ScriptMessages.text(
                        context.locale(),
                        "action-failed",
                        Integer.valueOf(call.line()),
                        Integer.valueOf(call.column()),
                        call.name(),
                        detail),
                call.line(),
                call.column(),
                failure);
    }

    private static final class SourcePosition {
        private final int line;
        private final int column;

        private SourcePosition(int line, int column) {
            this.line = line;
            this.column = column;
        }
    }

    private me.kzheart.klib.script.kether.core.QuestActionParser conditionalParser() {
        return me.kzheart.klib.script.kether.core.QuestActionParser.of(reader -> {
            ParsedAction<?> condition = reader.nextParsedAction();
            reader.expect("then");
            ParsedAction<?> accepted = reader.nextParsedAction();
            ParsedAction<?> rejected = null;
            if (reader.hasNext() && reader.peek() != '}') {
                reader.expect("else");
                rejected = reader.nextParsedAction();
            }
            final ParsedAction<?> elseAction = rejected;
            return new QuestAction<Object>() {
                @Override
                public CompletableFuture<Object> process(QuestContext.Frame frame) {
                    return frame.newFrame(condition).run().thenCompose(value -> {
                        ParsedAction<?> selected = InlineValues.truthy(value) ? accepted : elseAction;
                        return selected == null
                                ? CompletableFuture.completedFuture(null)
                                : frame.newFrame(selected).run();
                    });
                }
            };
        });
    }

    private me.kzheart.klib.script.kether.core.QuestActionParser namespaceParser() {
        return me.kzheart.klib.script.kether.core.QuestActionParser.of(reader -> {
            String namespace = reader.nextToken();
            ParsedAction<?> body = reader.nextParsedAction(namespace);
            return new QuestAction<Object>() {
                @Override
                public CompletableFuture<Object> process(QuestContext.Frame frame) {
                    return frame.newFrame(body).run();
                }
            };
        });
    }

    private me.kzheart.klib.script.kether.core.QuestActionParser unknownParser(final String name) {
        return me.kzheart.klib.script.kether.core.QuestActionParser.of(reader -> {
            List<String> arguments = readRemaining(reader);
            return new QuestAction<Object>() {
                @Override
                public CompletableFuture<Object> process(QuestContext.Frame frame) {
                    StringBuilder statement = new StringBuilder(name);
                    for (String argument : arguments) {
                        statement.append(' ').append(argument);
                    }
                    try {
                        return unknownResolver.resolve(statement.toString(), context(frame))
                                .toCompletableFuture();
                    } catch (RuntimeException failure) {
                        return failed(failure);
                    } catch (StackOverflowError failure) {
                        return failed(failure);
                    }
                }
            };
        });
    }

    private static List<String> readArguments(String name, QuestReader reader) {
        List<String> arguments = new ArrayList<String>();
        if ("tell".equals(name) || "get".equals(name) || "unset".equals(name)
                || "not".equals(name) || "perm".equals(name) || "delay".equals(name)) {
            arguments.add(reader.nextToken());
            return arguments;
        }
        if ("type".equals(name) || "eq".equals(name) || "ne".equals(name)
                || "gt".equals(name) || "gte".equals(name) || "lt".equals(name)
                || "lte".equals(name)) {
            arguments.add(reader.nextToken());
            arguments.add(reader.nextToken());
            return arguments;
        }
        if ("set".equals(name)) {
            arguments.add(reader.nextToken());
            String second = reader.nextToken();
            arguments.add(second);
            if ("to".equalsIgnoreCase(second)) {
                arguments.add(reader.nextToken());
            }
            return arguments;
        }
        if ("check".equals(name)) {
            String source = reader.nextToken();
            arguments.add(source);
            arguments.add(reader.nextToken());
            arguments.add(reader.nextToken());
            if ("papi".equalsIgnoreCase(source) || "player".equalsIgnoreCase(source)) {
                arguments.add(reader.nextToken());
            }
            return arguments;
        }
        if ("all".equals(name) || "any".equals(name)) {
            reader.expect("[");
            arguments.add("[");
            while (reader.hasNext() && reader.peek() != ']') {
                arguments.add(reader.nextToken());
            }
            reader.expect("]");
            arguments.add("]");
            return arguments;
        }
        if ("command".equals(name)) {
            String first = reader.nextToken();
            arguments.add(first);
            if ("inline".equalsIgnoreCase(first)) {
                arguments.add(reader.nextToken());
                reader.expect("as");
                arguments.add("as");
                arguments.add(reader.nextToken());
                return arguments;
            }
            return appendRemaining(arguments, reader);
        }
        if ("literal".equals(name)) {
            arguments.add(reader.nextToken());
            return arguments;
        }
        return appendRemaining(arguments, reader);
    }

    private static List<String> appendRemaining(List<String> arguments, QuestReader reader) {
        arguments.addAll(readRemaining(reader));
        return arguments;
    }

    private static List<String> readRemaining(QuestReader reader) {
        List<String> arguments = new ArrayList<String>();
        while (!reader.hasLineBreakBeforeNextToken()
                && reader.hasNext()
                && reader.peek() != '}') {
            arguments.add(reader.nextToken());
        }
        return arguments;
    }

    private static ScriptContext context(QuestContext.Frame frame) {
        ScriptContext context = frame.variables().getOrNull(CONTEXT_VARIABLE);
        if (context == null) {
            throw new IllegalStateException("Kether frame has no ScriptContext");
        }
        return context;
    }

    private static EvaluationState evaluationState(QuestContext.Frame frame) {
        EvaluationState state = frame.variables().getOrNull(EVALUATION_STATE_VARIABLE);
        if (state == null) {
            throw new IllegalStateException("Kether frame has no evaluation state");
        }
        return state;
    }

    private static String normalizeSource(String source) {
        String normalized = normalizeSeparators(source)
                .replaceAll("(?m)^([ \\t]*)#", "$1//");
        if (normalized.trim().startsWith("def ")) {
            return normalized;
        }
        return "def main = {\n" + normalized + "\n}";
    }

    private static void validateCompilationBudget(String source) {
        if (source.length() > MAX_SOURCE_CHARS) {
            throw compilationFailure(
                    "source length exceeds " + MAX_SOURCE_CHARS + " characters", null);
        }
        int actions = 0;
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        boolean lineComment = false;
        boolean actionStart = true;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            if (lineComment) {
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                    actionStart = true;
                }
                continue;
            }
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '/' && index + 1 < source.length()
                    && source.charAt(index + 1) == '/') {
                lineComment = true;
                index++;
                continue;
            }
            if (current == '#') {
                lineComment = true;
                continue;
            }
            if (current == '\'' || current == '"') {
                quote = current;
                actionStart = false;
                continue;
            }
            if (current == '{') {
                depth++;
                if (depth > MAX_SYNTAX_DEPTH) {
                    throw compilationFailure(
                            "syntax nesting exceeds " + MAX_SYNTAX_DEPTH, null);
                }
                actionStart = true;
                continue;
            }
            if (current == '}') {
                if (depth > 0) {
                    depth--;
                }
                actionStart = false;
                continue;
            }
            if (current == '\n' || current == '\r' || current == ';') {
                actionStart = true;
                continue;
            }
            if (Character.isWhitespace(current)) {
                continue;
            }
            if (actionStart) {
                actions++;
                if (actions > MAX_ACTIONS) {
                    throw compilationFailure(
                            "action count exceeds " + MAX_ACTIONS, null);
                }
                actionStart = false;
            }
        }
    }

    private static ScriptException compilationFailure(String detail, Throwable cause) {
        return new ScriptException(
                "compilation-limit",
                "Script compilation rejected: " + detail,
                1,
                1,
                cause);
    }

    private static String normalizeSeparators(String source) {
        StringBuilder result = new StringBuilder(source.length());
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (quote != 0 && current == '\\') {
                escaped = true;
            } else if (quote != 0 && current == quote) {
                quote = 0;
            } else if (quote == 0 && (current == '\'' || current == '"')) {
                quote = current;
            }
            result.append(current == ';' && quote == 0 ? '\n' : current);
        }
        return result.toString();
    }

    private static <T> CompletableFuture<T> failed(Throwable failure) {
        CompletableFuture<T> result = new CompletableFuture<T>();
        result.completeExceptionally(failure);
        return result;
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
    }

    private final class EvaluationState {
        private final AtomicInteger nestedEvaluations = new AtomicInteger();

        private CompletionStage<Object> evalNested(String source, ScriptContext context) {
            int active = nestedEvaluations.incrementAndGet();
            if (active > MAX_NESTED_EVALUATIONS) {
                nestedEvaluations.decrementAndGet();
                return failed(new IllegalStateException(
                        "script nesting exceeds " + MAX_NESTED_EVALUATIONS));
            }
            CompletionStage<Object> execution;
            try {
                execution = CoreScriptRuntime.this.eval(source, context, this);
            } catch (RuntimeException failure) {
                nestedEvaluations.decrementAndGet();
                return failed(failure);
            }
            CompletableFuture<Object> result = new CompletableFuture<Object>();
            execution.whenComplete((value, failure) -> {
                nestedEvaluations.decrementAndGet();
                if (failure == null) {
                    result.complete(value);
                } else {
                    result.completeExceptionally(unwrap(failure));
                }
            });
            return result;
        }
    }

    private static final class CacheKey {
        private final String source;
        private final List<String> namespaces;

        private CacheKey(String source, List<String> namespaces) {
            this.source = source;
            this.namespaces = Collections.unmodifiableList(
                    new ArrayList<String>(namespaces));
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof CacheKey)) {
                return false;
            }
            CacheKey that = (CacheKey) other;
            return source.equals(that.source) && namespaces.equals(that.namespaces);
        }

        @Override
        public int hashCode() {
            return 31 * source.hashCode() + namespaces.hashCode();
        }
    }

    private static final class CacheEntry {
        private final Quest quest;
        private final int sourceChars;

        private CacheEntry(Quest quest, int sourceChars) {
            this.quest = quest;
            this.sourceChars = sourceChars;
        }
    }
}
