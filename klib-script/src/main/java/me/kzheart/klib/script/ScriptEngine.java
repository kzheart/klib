package me.kzheart.klib.script;

import java.util.concurrent.CompletionStage;

/** 执行脚本而不公开实现特定的运行时。 */
public interface ScriptEngine {

    CompletionStage<Object> eval(String script, ScriptContext context);

    CompletionStage<Boolean> evalCondition(String script, ScriptContext context);
}
