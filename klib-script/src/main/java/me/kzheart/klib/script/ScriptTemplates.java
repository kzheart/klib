/* VariableReader/KetherFunction semantics: Copyright (c) 2018 Bkm016, MIT.
 * See THIRD_PARTY_NOTICES.md. */
package me.kzheart.klib.script;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** 显式选择即时求值语义的嵌套 {{...}} 文本模板；不改变 inline 动作的等待行为。 */
public final class ScriptTemplates {
    private static final String START = "{{";
    private static final String END = "}}";

    private ScriptTemplates() { }

    /**
     * 从最内层逐段求值；反斜线转义的定界符保留为文本。每段仅调用 getNow(null)，
     * 未完成的异步段和 null 结果均写为字符串 "null"，不会等待或取消其后续执行。
     * 已失败/取消的段向调用方抛出异常，错误回退应由调用方明确处理。
     * evaluator 决定各段的宿主上下文/变量隔离，允许替换结果继续产生嵌套模板。
     */
    public static String renderImmediate(String source,
            Function<String, ? extends CompletionStage<?>> evaluator) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(evaluator, "evaluator");
        String result = source;
        while (true) {
            int end = indexOf(result, END);
            if (end < 0) break;
            int start = lastIndexOf(result.substring(0, end), START);
            if (start < 0) break;
            String expression = format(result.substring(start + START.length(), end));
            Object value = Objects.requireNonNull(evaluator.apply(expression), "evaluation stage")
                    .toCompletableFuture().getNow(null);
            result = result.substring(0, start) + String.valueOf(value)
                    + result.substring(end + END.length());
        }
        return format(result);
    }

    private static int indexOf(String source, String marker) {
        int position = 0;
        while (true) {
            int found = source.indexOf(marker, position);
            if (found <= 0 || source.charAt(found - 1) != '\\') return found;
            position = found + marker.length();
        }
    }

    private static int lastIndexOf(String source, String marker) {
        int position = source.length();
        while (true) {
            int found = source.lastIndexOf(marker, position);
            if (found <= 0 || source.charAt(found - 1) != '\\') return found;
            position = found - marker.length();
        }
    }

    private static String format(String source) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < source.length();) {
            if (source.charAt(index) == '\\'
                    && (source.startsWith(START, index + 1) || source.startsWith(END, index + 1))) {
                result.append(source, index + 1, index + 3);
                index += 3;
            } else result.append(source.charAt(index++));
        }
        return result.toString();
    }
}
