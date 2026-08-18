package me.kzheart.klib.script;

import java.util.concurrent.CompletionStage;

/** 组合解析器的执行回调。 */
@FunctionalInterface
public interface CombinedAction {

    CompletionStage<Object> execute(StatementArguments arguments, ScriptContext context);
}
