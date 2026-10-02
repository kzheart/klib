package me.kzheart.klib.script;

import java.util.Map;

/** {@code js}/{@code javascript}/{@code $} 语句的脚本求值；bindings 含 sender、server 与当前变量。 */
@FunctionalInterface
public interface JavaScriptEvaluator {

    Object eval(String source, Map<String, Object> bindings);
}
