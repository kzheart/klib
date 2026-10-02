/* Kether action semantics: Copyright (c) 2018 Bkm016, MIT. See THIRD_PARTY_NOTICES.md. */
package me.kzheart.klib.script;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import me.kzheart.klib.script.kether.core.ArgTypes;
import me.kzheart.klib.script.kether.core.ExitStatus;
import me.kzheart.klib.script.kether.core.ParsedAction;
import me.kzheart.klib.script.kether.core.Quest;
import me.kzheart.klib.script.kether.core.QuestAction;
import me.kzheart.klib.script.kether.core.QuestActionParser;
import me.kzheart.klib.script.kether.core.QuestContext;
import me.kzheart.klib.script.kether.core.QuestFuture;
import me.kzheart.klib.script.kether.core.QuestReader;
import me.kzheart.klib.script.kether.core.SimpleReader;

import static me.kzheart.klib.script.KetherSupport.action;
import static me.kzheart.klib.script.KetherSupport.bool;
import static me.kzheart.klib.script.KetherSupport.completed;
import static me.kzheart.klib.script.KetherSupport.consume;
import static me.kzheart.klib.script.KetherSupport.elements;
import static me.kzheart.klib.script.KetherSupport.follow;
import static me.kzheart.klib.script.KetherSupport.integer;
import static me.kzheart.klib.script.KetherSupport.now;
import static me.kzheart.klib.script.KetherSupport.run;
import static me.kzheart.klib.script.KetherSupport.setRoot;
import static me.kzheart.klib.script.KetherSupport.takeBreak;
import static me.kzheart.klib.script.KetherSupport.text;

/** 流程控制语句：等待、跳转、循环、并发与日志。 */
final class FlowScriptActions {
    private FlowScriptActions() { }

    static void install(StatementRegistry registry) {
        registry.registerBuiltinKether("import", QuestActionParser.of(reader -> {
            String namespace = reader.nextToken();
            readerNamespace(reader, "import").addNamespace(namespace);
            return now(frame -> null);
        }));
        registry.registerBuiltinKether("release", QuestActionParser.of(reader -> {
            String namespace = reader.nextToken();
            readerNamespace(reader, "release").removeNamespace(namespace);
            return now(frame -> null);
        }));
        registry.registerBuiltinKether("pause", QuestActionParser.of(reader -> action(frame -> new CompletableFuture<Object>())));
        // 与原 Workspace.terminateScript 一致：标记退出状态，执行循环停止，脚本正常结束。
        QuestActionParser exit = QuestActionParser.of(reader -> now(frame -> {
            if (!frame.context().getExitStatus().isPresent()) frame.context().setExitStatus(ExitStatus.paused());
            return null;
        }));
        for (String name : new String[]{"exit", "stop", "terminate"}) registry.registerBuiltinKether(name, exit);
        log(registry, Level.INFO, "log", "print", "info");
        log(registry, Level.WARNING, "warn", "warning");
        log(registry, Level.SEVERE, "error", "severe");
        QuestActionParser wait = QuestActionParser.of(reader -> {
            Duration duration = reader.next(ArgTypes.DURATION);
            return action(frame -> {
                ScriptContext context = CoreScriptRuntime.context(frame);
                return context.requireService(DelayScheduler.class).delay(duration).toCompletableFuture().thenApply(ignored -> {
                    // 原框架：等待期间玩家离线则停止脚本。
                    Object sender = context.sender().orElse(null);
                    ScriptSenderQuery query = context.service(ScriptSenderQuery.class).orElse(null);
                    if (sender != null && query != null && query.isPlayer(sender) && !query.isOnline(sender)
                            && !frame.context().getExitStatus().isPresent()) {
                        frame.context().setExitStatus(ExitStatus.paused());
                    }
                    return (Object) null;
                });
            });
        });
        registry.registerBuiltinKether("wait", wait);
        registry.registerBuiltinKether("sleep", wait);
        registry.registerBuiltinKether("async", QuestActionParser.of(reader -> {
            ParsedAction<Object> async = reader.nextValue();
            return now(frame -> new QuestFuture<Object>(async, run(frame, async)));
        }));
        registry.registerBuiltinKether("await", QuestActionParser.of(reader -> {
            ParsedAction<?> awaited = reader.nextValue();
            return action(frame -> {
                CompletableFuture<Object> future = new CompletableFuture<Object>();
                run(frame, awaited).thenAccept(QuestFuture.complete(future));
                return future;
            });
        }));
        registry.registerBuiltinKether("await_all", QuestActionParser.of(reader -> {
            List<ParsedAction<?>> actions = reader.next(ArgTypes.listOf(ArgTypes.ACTION));
            return action(frame -> CompletableFuture.allOf(start(frame, actions)).thenApply(ignored -> (Object) null));
        }));
        registry.registerBuiltinKether("await_any", QuestActionParser.of(reader -> {
            List<ParsedAction<?>> actions = reader.next(ArgTypes.listOf(ArgTypes.ACTION));
            return action(frame -> CompletableFuture.anyOf(start(frame, actions)));
        }));
        registry.registerBuiltinKether("call", QuestActionParser.of(reader -> {
            String label = reader.nextToken();
            return action(frame -> {
                QuestContext.Frame child = frame.newFrame(label);
                child.setNext(block(frame, label));
                frame.addClosable(child);
                return child.run();
            });
        }));
        registry.registerBuiltinKether("goto", QuestActionParser.of(reader -> {
            String label = reader.nextToken();
            return now(frame -> { frame.setNext(block(frame, label)); return null; });
        }));
        registry.registerBuiltinKether("repeat", QuestActionParser.of(reader -> {
            ParsedAction<?> times = reader.nextValue();
            ParsedAction<?> repeated = reader.nextValue();
            // 原实现一次性启动全部迭代，全部完成后返回 null。
            return action(frame -> follow(frame, run(frame, times), count -> {
                List<CompletableFuture<Object>> futures = new ArrayList<CompletableFuture<Object>>();
                for (int index = 0; index < integer(count); index++) futures.add(run(frame, repeated));
                return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).thenApply(ignored -> (Object) null);
            }));
        }));
        registry.registerBuiltinKether("seq", QuestActionParser.of(reader -> {
            List<ParsedAction<?>> actions = reader.next(ArgTypes.listOf(ArgTypes.ACTION));
            return action(frame -> sequence(frame, actions, 0, null));
        }));
        registry.registerBuiltinKether("break", QuestActionParser.of(reader -> now(frame -> {
            setRoot(frame, KetherSupport.BREAK_LOOP, Boolean.TRUE);
            return null;
        })));
        registry.registerBuiltinKether("for", QuestActionParser.of(reader -> {
            String key = reader.nextToken();
            reader.expect("in");
            ParsedAction<?> values = reader.nextValue();
            reader.expect("then");
            ParsedAction<?> body = reader.nextValue();
            return action(frame -> iterate(frame, key, values, body, false));
        }));
        registry.registerBuiltinKether("map", QuestActionParser.of(reader -> {
            String key = reader.nextToken();
            reader.expect("in");
            ParsedAction<?> values = reader.nextValue();
            reader.expect("with");
            ParsedAction<?> body = reader.nextValue();
            return action(frame -> iterate(frame, key, values, body, true));
        }));
        registry.registerBuiltinKether("while", QuestActionParser.of(reader -> {
            ParsedAction<?> condition = reader.nextValue();
            reader.expect("then");
            ParsedAction<?> body = reader.nextValue();
            return action(frame -> {
                CompletableFuture<Object> future = new CompletableFuture<Object>();
                loop(frame, condition, body, future);
                return future;
            });
        }));
        registry.registerBuiltinKether("optional", QuestActionParser.of(reader -> {
            ParsedAction<?> value = reader.nextValue();
            ParsedAction<?> otherwise = consume(reader, "else") ? reader.nextValue() : null;
            return action(frame -> follow(frame, run(frame, value), result -> result != null || otherwise == null
                    ? completed(result) : run(frame, otherwise)));
        }));
        registry.registerBuiltinKether("pass", QuestActionParser.of(reader -> now(frame -> "")));
        QuestActionParser variables = QuestActionParser.of(reader -> now(frame -> {
            List<Object> keys = new ArrayList<Object>();
            for (String key : ScriptFrames.variables(frame).keySet()) if (!ScriptFrames.isInternal(key)) keys.add(key);
            return keys;
        }));
        registry.registerBuiltinKether("vars", variables);
        registry.registerBuiltinKether("variables", variables);
    }

    private static void log(StatementRegistry registry, Level level, String... names) {
        QuestActionParser parser = QuestActionParser.of(reader -> {
            ParsedAction<?> message = reader.nextValue();
            return action(frame -> follow(frame, run(frame, message), value -> {
                CoreScriptRuntime.context(frame).requireService(ScriptLogger.class).log(level, text(value));
                return completed(null);
            }));
        });
        for (String name : names) registry.registerBuiltinKether(name, parser);
    }

    private static SimpleReader readerNamespace(QuestReader reader, String statement) {
        if (!(reader instanceof SimpleReader)) throw new IllegalArgumentException(statement + " requires a native QuestReader");
        return (SimpleReader) reader;
    }

    private static Quest.Block block(QuestContext.Frame frame, String label) {
        Quest.Block block = frame.context().getQuest().getBlocks().get(label);
        if (block == null) throw new IllegalStateException("block " + label + " not found");
        return block;
    }

    private static CompletableFuture<?>[] start(QuestContext.Frame frame, List<ParsedAction<?>> actions) {
        CompletableFuture<?>[] futures = new CompletableFuture<?>[actions.size()];
        for (int index = 0; index < actions.size(); index++) futures[index] = run(frame, actions.get(index));
        return futures;
    }

    /** 顺序执行并返回最后一个结果；出错时以已有的最后结果结束。 */
    private static CompletableFuture<Object> sequence(QuestContext.Frame frame, List<ParsedAction<?>> actions, int index, Object last) {
        if (index >= actions.size()) return completed(last);
        return run(frame, actions.get(index)).handle((value, failure) -> failure == null
                ? sequence(frame, actions, index + 1, value) : completed(last)).thenCompose(future -> future);
    }

    /** for / map 共用：遍历元素并写入 key、key-key、key-value 变量，结束或 break 后移除。 */
    private static CompletableFuture<Object> iterate(QuestContext.Frame frame, String key, ParsedAction<?> values,
                                                     ParsedAction<?> body, boolean collect) {
        List<Object> results = new ArrayList<Object>();
        CompletableFuture<Object> future = new CompletableFuture<Object>();
        run(frame, values).whenComplete((source, failure) -> {
            if (failure != null) { finish(frame, key, future, collect ? results : null); return; }
            next(frame, key, elements(source), 0, body, collect ? results : null, future);
        });
        return future;
    }

    /** 同步完成的迭代在循环内继续，只有异步迭代才挂起续接，避免长循环耗尽调用栈。 */
    private static void next(QuestContext.Frame frame, String key, List<Object> items, int start, ParsedAction<?> body,
                             List<Object> results, CompletableFuture<Object> future) {
        for (int index = start; index < items.size(); index++) {
            Object element = items.get(index);
            if (element instanceof Map.Entry<?, ?>) {
                frame.variables().set(key + "-key", ((Map.Entry<?, ?>) element).getKey());
                frame.variables().set(key + "-value", ((Map.Entry<?, ?>) element).getValue());
            }
            frame.variables().set(key, element);
            CompletableFuture<Object> step = run(frame, body);
            if (!step.isDone()) {
                final int resume = index + 1;
                step.whenCompleteAsync((value, failure) -> {
                    if (failure != null) { finish(frame, key, future, results); return; }
                    if (results != null && value != null) results.add(value);
                    if (takeBreak(frame)) finish(frame, key, future, results);
                    else next(frame, key, items, resume, body, results, future);
                }, frame.context().getExecutor());
                return;
            }
            Object value;
            try { value = step.join(); }
            catch (RuntimeException failure) { finish(frame, key, future, results); return; }
            if (results != null && value != null) results.add(value);
            if (takeBreak(frame)) { finish(frame, key, future, results); return; }
        }
        finish(frame, key, future, results);
    }

    private static void finish(QuestContext.Frame frame, String key, CompletableFuture<Object> future, List<Object> results) {
        frame.variables().remove(key);
        frame.variables().remove(key + "-key");
        frame.variables().remove(key + "-value");
        future.complete(results);
    }

    private static void loop(QuestContext.Frame frame, ParsedAction<?> condition, ParsedAction<?> body, CompletableFuture<Object> future) {
        while (true) {
            CompletableFuture<Object> check = run(frame, condition);
            if (!check.isDone()) {
                check.whenCompleteAsync((value, failure) -> {
                    if (failure != null || !bool(value)) future.complete(null);
                    else afterCondition(frame, condition, body, future);
                }, frame.context().getExecutor());
                return;
            }
            Object value;
            try { value = check.join(); }
            catch (RuntimeException failure) { future.complete(null); return; }
            if (!bool(value)) { future.complete(null); return; }
            CompletableFuture<Object> step = run(frame, body);
            if (!step.isDone()) {
                step.whenCompleteAsync((ignored, failure) -> {
                    if (failure != null || takeBreak(frame)) future.complete(null);
                    else loop(frame, condition, body, future);
                }, frame.context().getExecutor());
                return;
            }
            try { step.join(); }
            catch (RuntimeException failure) { future.complete(null); return; }
            if (takeBreak(frame)) { future.complete(null); return; }
        }
    }

    private static void afterCondition(QuestContext.Frame frame, ParsedAction<?> condition, ParsedAction<?> body,
                                       CompletableFuture<Object> future) {
        run(frame, body).whenCompleteAsync((ignored, failure) -> {
            if (failure != null || takeBreak(frame)) future.complete(null);
            else loop(frame, condition, body, future);
        }, frame.context().getExecutor());
    }
}
