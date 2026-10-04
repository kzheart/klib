package me.kzheart.klib.script;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import me.kzheart.klib.script.kether.core.ParsedAction;
import me.kzheart.klib.script.kether.core.QuestAction;
import me.kzheart.klib.script.kether.core.QuestActionParser;
import me.kzheart.klib.script.kether.core.QuestReader;

/** 布尔列表保留完整原生动作树，按顺序求值全部项。 */
final class NativeLogicalActions {
    private NativeLogicalActions() { }

    static void install(StatementRegistry registry) {
        registry.registerBuiltinKether("all", QuestActionParser.of(reader -> group(reader, true)));
        registry.registerBuiltinKether("any", QuestActionParser.of(reader -> group(reader, false)));
    }

    private static QuestAction<Object> group(QuestReader reader, boolean all) {
        reader.expect("[");
        List<ParsedAction<?>> values = new ArrayList<ParsedAction<?>>();
        while (reader.hasNext() && reader.peek() != ']') values.add(reader.nextValue());
        reader.expect("]");
        return KetherSupport.action(frame -> {
            CompletableFuture<Object> result = KetherSupport.completed(Boolean.valueOf(all));
            for (ParsedAction<?> value : values) {
                result = KetherSupport.follow(frame, result, previous ->
                        KetherSupport.follow(frame, KetherSupport.run(frame, value), current -> {
                            boolean truth = InlineValues.truthy(current);
                            return KetherSupport.completed(Boolean.valueOf(all
                                    ? ((Boolean) previous).booleanValue() && truth
                                    : ((Boolean) previous).booleanValue() || truth));
                        }));
            }
            return result;
        });
    }
}
