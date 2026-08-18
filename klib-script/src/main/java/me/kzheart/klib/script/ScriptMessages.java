package me.kzheart.klib.script;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class ScriptMessages {

    private static final Map<String, String> ZH = new HashMap<String, String>();
    private static final Map<String, String> EN = new HashMap<String, String>();

    static {
        ZH.put("unknown-statement", "第 {0} 行第 {1} 列：未知语句“{2}”");
        ZH.put("invalid-syntax", "第 {0} 行第 {1} 列：语法错误：{2}");
        ZH.put("action-failed", "第 {0} 行第 {1} 列：语句“{2}”执行失败：{3}");
        ZH.put("invalid-condition", "条件结果不是布尔值：{0}");
        ZH.put("missing-argument", "缺少参数“{0}”");
        ZH.put("invalid-number", "“{0}”不是有效数字");
        ZH.put("continuation-executor-required", "异步脚本动作需要显式提供续接 Executor");

        EN.put("unknown-statement", "Line {0}, column {1}: unknown statement ''{2}''");
        EN.put("invalid-syntax", "Line {0}, column {1}: syntax error: {2}");
        EN.put("action-failed", "Line {0}, column {1}: statement ''{2}'' failed: {3}");
        EN.put("invalid-condition", "Condition result is not boolean: {0}");
        EN.put("missing-argument", "Missing argument ''{0}''");
        EN.put("invalid-number", "''{0}'' is not a valid number");
        EN.put("continuation-executor-required",
                "Asynchronous script actions require an explicit continuation Executor");
    }

    private ScriptMessages() {
    }

    static String text(Locale locale, String key, Object... arguments) {
        Map<String, String> messages = "zh".equals(locale.getLanguage()) ? ZH : EN;
        String template = messages.get(key);
        return MessageFormat.format(template == null ? key : template, arguments);
    }
}
