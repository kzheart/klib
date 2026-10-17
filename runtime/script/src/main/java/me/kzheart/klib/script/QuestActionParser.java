package me.kzheart.klib.script;

import java.util.concurrent.CompletionStage;

/** 解析并执行一次动作调用。 */
@FunctionalInterface
public interface QuestActionParser {

    CompletionStage<Object> execute(StatementCall call, ScriptContext context);
}
