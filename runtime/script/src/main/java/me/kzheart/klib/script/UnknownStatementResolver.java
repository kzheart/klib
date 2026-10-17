package me.kzheart.klib.script;

import java.util.concurrent.CompletionStage;

/** 用于其他运行时所提供语句的可选回退。 */
@FunctionalInterface
public interface UnknownStatementResolver {

    CompletionStage<Object> resolve(String statement, ScriptContext context);
}
