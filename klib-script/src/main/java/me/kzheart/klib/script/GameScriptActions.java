/* Kether action semantics: Copyright (c) 2018 Bkm016, MIT. See THIRD_PARTY_NOTICES.md. */
package me.kzheart.klib.script;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import me.kzheart.klib.script.kether.core.ParsedAction;
import me.kzheart.klib.script.kether.core.QuestActionParser;
import me.kzheart.klib.script.kether.core.QuestContext;
import me.kzheart.klib.script.kether.core.QuestReader;

import static me.kzheart.klib.script.KetherSupport.action;
import static me.kzheart.klib.script.KetherSupport.completed;
import static me.kzheart.klib.script.KetherSupport.consume;
import static me.kzheart.klib.script.KetherSupport.elements;
import static me.kzheart.klib.script.KetherSupport.follow;
import static me.kzheart.klib.script.KetherSupport.integer;
import static me.kzheart.klib.script.KetherSupport.now;
import static me.kzheart.klib.script.KetherSupport.number;
import static me.kzheart.klib.script.KetherSupport.run;
import static me.kzheart.klib.script.KetherSupport.setRoot;
import static me.kzheart.klib.script.KetherSupport.text;

/** 需要服务器能力的原框架语句，经 {@link ScriptPlatform} 交给宿主执行。 */
final class GameScriptActions {
    private GameScriptActions() { }

    static void install(StatementRegistry registry) {
        registry.registerBuiltinKether("actionbar", textAction((frame, message) -> {
            platform(frame).actionBar(player(frame), withSender(frame, message));
            return null;
        }));
        QuestActionParser broadcast = textAction((frame, message) -> {
            platform(frame).broadcast(withSender(frame, message));
            return null;
        });
        registry.registerBuiltinKether("broadcast", broadcast);
        registry.registerBuiltinKether("bc", broadcast);
        registry.registerBuiltinKether("players", QuestActionParser.of(reader -> now(frame ->
                new ArrayList<Object>(platform(frame).onlinePlayerNames()))));
        registry.registerBuiltinKether("switch", textAction((frame, target) -> {
            ScriptPlatform platform = platform(frame);
            Object sender = "console".equals(target) || "server".equals(target) ? platform.console() : platform.player(target);
            setRoot(frame, ScriptContext.SENDER_OVERRIDE, sender);
            return null;
        }));
        registry.registerBuiltinKether("title", QuestActionParser.of(reader -> {
            ParsedAction<?> title = reader.nextValue();
            ParsedAction<?> subtitle = consume(reader, "subtitle") ? reader.nextValue() : null;
            ParsedAction<?>[] times = times(reader);
            return action(frame -> follow(frame, run(frame, title), main ->
                    follow(frame, subtitle == null ? completed(null) : run(frame, subtitle), sub ->
                    follow(frame, evaluate(frame, times), timing -> {
                        Object player = player(frame);
                        String name = name(frame, player);
                        platform(frame).title(player, text(main).replace("@sender", name),
                                sub == null ? "§r" : text(sub).replace("@sender", name),
                                integer(timing.get(0)), integer(timing.get(1)), integer(timing.get(2)));
                        return completed(null);
                    }))));
        }));
        registry.registerBuiltinKether("subtitle", QuestActionParser.of(reader -> {
            ParsedAction<?> subtitle = reader.nextValue();
            ParsedAction<?>[] times = times(reader);
            return action(frame -> follow(frame, run(frame, subtitle), sub -> follow(frame, evaluate(frame, times), timing -> {
                Object player = player(frame);
                platform(frame).title(player, "§r", text(sub).replace("@sender", name(frame, player)),
                        integer(timing.get(0)), integer(timing.get(1)), integer(timing.get(2)));
                return completed(null);
            })));
        }));
        QuestActionParser location = QuestActionParser.of(reader -> {
            ParsedAction<?>[] args = {reader.nextValue(), reader.nextValue(), reader.nextValue(), reader.nextValue()};
            ParsedAction<?>[] rotation = consume(reader, "and") ? new ParsedAction<?>[]{reader.nextValue(), reader.nextValue()} : null;
            return action(frame -> follow(frame, evaluate(frame, args), position ->
                    follow(frame, rotation == null ? CompletableFuture.completedFuture(Arrays.<Object>asList(0, 0)) : evaluate(frame, rotation), angles ->
                            completed(platform(frame).location(text(position.get(0)), number(position.get(1)), number(position.get(2)),
                                    number(position.get(3)), (float) number(angles.get(0)), (float) number(angles.get(1)))))));
        });
        registry.registerBuiltinKether("location", location);
        registry.registerBuiltinKether("loc", location);
        registry.registerBuiltinKether("sound", QuestActionParser.of(reader -> {
            ParsedAction<?> sound = reader.nextValue();
            // 原框架省略 by 时音量与音调均为 0。
            ParsedAction<?>[] volume = consume(reader, "by", "with") ? new ParsedAction<?>[]{reader.nextValue(), reader.nextValue()} : null;
            return action(frame -> follow(frame, run(frame, sound), name ->
                    follow(frame, volume == null ? CompletableFuture.completedFuture(Arrays.<Object>asList(0, 0)) : evaluate(frame, volume), values -> {
                        platform(frame).playSound(player(frame), soundName(text(name)), (float) number(values.get(0)), (float) number(values.get(1)));
                        return completed(null);
                    })));
        }));
        registry.registerBuiltinKether("stopsound", textAction((frame, name) -> {
            platform(frame).stopSound(player(frame), soundName(name));
            return null;
        }));
        registry.registerBuiltinKether("itemstack", textAction((frame, name) -> platform(frame).itemStack(name)));
        registry.registerBuiltinKether("material", textAction((frame, name) -> platform(frame).material(name)));
        registry.registerBuiltinKether("scoreboard", QuestActionParser.of(reader -> {
            ParsedAction<?> content = reader.nextValue();
            return action(frame -> follow(frame, run(frame, content), value -> {
                List<String> lines = null;
                if (value instanceof Collection<?> || (value != null && value.getClass().isArray())) {
                    lines = new ArrayList<String>();
                    for (Object line : elements(value)) lines.add(String.valueOf(line));
                } else if (value != null) {
                    lines = Arrays.asList(NativeCheckActions.trimIndent(value.toString()).split("\\r\\n|\\n|\\r", -1));
                }
                platform(frame).scoreboard(player(frame), lines);
                return completed(null);
            }));
        }));
        QuestActionParser javascript = QuestActionParser.of(reader -> {
            String source = NativeCheckActions.trimIndent(reader.nextToken());
            return now(frame -> {
                ScriptContext context = CoreScriptRuntime.context(frame);
                Map<String, Object> bindings = new LinkedHashMap<String, Object>();
                bindings.put("sender", context.sender().orElse(null));
                bindings.put("server", context.service(ScriptPlatform.class).map(ScriptPlatform::console).orElse(null));
                for (Map.Entry<String, Object> entry : ScriptFrames.variables(frame).entrySet()) {
                    if (!ScriptFrames.isInternal(entry.getKey())) bindings.put(entry.getKey(), entry.getValue());
                }
                return context.requireService(JavaScriptEvaluator.class).eval(source, bindings);
            });
        });
        for (String name : new String[]{"$", "js", "javascript"}) registry.registerBuiltinKether(name, javascript);
    }

    private static String soundName(String name) {
        return name.startsWith("resource:") ? name : name.replace('.', '_').toUpperCase(Locale.ROOT);
    }

    /** [by|with 淡入 停留 淡出]，缺省为 0 20 0。 */
    private static ParsedAction<?>[] times(QuestReader reader) {
        return consume(reader, "by", "with") ? new ParsedAction<?>[]{reader.nextValue(), reader.nextValue(), reader.nextValue()} : null;
    }

    private static CompletableFuture<List<Object>> evaluate(QuestContext.Frame frame, ParsedAction<?>[] actions) {
        if (actions == null) return CompletableFuture.completedFuture(Arrays.<Object>asList(0, 20, 0));
        return CollectionScriptActions.collect(frame, Arrays.asList(actions));
    }

    private static QuestActionParser textAction(BiFunction<QuestContext.Frame, String, Object> operation) {
        return QuestActionParser.of(reader -> {
            ParsedAction<?> input = reader.nextValue();
            return action(frame -> follow(frame, run(frame, input), value -> completed(operation.apply(frame, text(value)))));
        });
    }

    static ScriptPlatform platform(QuestContext.Frame frame) {
        return CoreScriptRuntime.context(frame).requireService(ScriptPlatform.class);
    }

    /** 当前发送者必须是玩家，否则与原框架一样报 No player selected。 */
    static Object player(QuestContext.Frame frame) {
        ScriptContext context = CoreScriptRuntime.context(frame);
        Object sender = context.sender().orElse(null);
        if (sender == null || !context.requireService(ScriptSenderQuery.class).isPlayer(sender)) {
            throw new IllegalStateException("No player selected.");
        }
        return sender;
    }

    private static String name(QuestContext.Frame frame, Object player) {
        return String.valueOf(CoreScriptRuntime.context(frame).requireService(ScriptSenderQuery.class).name(player));
    }

    private static String withSender(QuestContext.Frame frame, String message) {
        if (!message.contains("@sender")) return message;
        Object sender = CoreScriptRuntime.context(frame).sender().orElse(null);
        return message.replace("@sender", sender == null ? "null" : name(frame, sender));
    }
}
