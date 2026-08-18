package me.kzheart.klib.script;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class InlineValues {

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}|\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");

    private InlineValues() {
    }

    static Object value(String source, ScriptContext context) {
        if (source.length() > 1 && source.charAt(0) == '&') {
            return context.variableOrNull(source.substring(1));
        }
        if (source.length() > 1 && source.charAt(0) == '*') {
            return source.substring(1);
        }
        Matcher matcher = VARIABLE.matcher(source);
        if (matcher.matches()) {
            return context.variableOrNull(variableName(matcher));
        }
        String expanded = text(source, context);
        if ("true".equalsIgnoreCase(expanded)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(expanded)) {
            return Boolean.FALSE;
        }
        if ("null".equalsIgnoreCase(expanded)) {
            return null;
        }
        try {
            return new BigDecimal(expanded);
        } catch (NumberFormatException ignored) {
            return expanded;
        }
    }

    static String text(String source, ScriptContext context) {
        if (source.length() > 1 && source.charAt(0) == '&') {
            Object referenced = context.variableOrNull(source.substring(1));
            return referenced == null ? "" : String.valueOf(referenced);
        }
        if (source.length() > 1 && source.charAt(0) == '*') {
            return source.substring(1);
        }
        Matcher matcher = VARIABLE.matcher(source);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            Object value = context.variableOrNull(variableName(matcher));
            matcher.appendReplacement(result, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(result);
        if (result.length() > 1 && result.charAt(0) == '$' && isSimpleName(result.substring(1))) {
            Object value = context.variableOrNull(result.substring(1));
            return value == null ? "" : String.valueOf(value);
        }
        return result.toString();
    }

    static boolean truthy(Object value) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        if (value instanceof Number) {
            return new BigDecimal(String.valueOf(value)).compareTo(BigDecimal.ZERO) != 0;
        }
        if (value instanceof CharSequence) {
            String text = value.toString();
            return !text.isEmpty() && !"false".equalsIgnoreCase(text) && !"0".equals(text);
        }
        return value != null;
    }

    private static String variableName(Matcher matcher) {
        return matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
    }

    private static boolean isSimpleName(String value) {
        return value.matches("[A-Za-z0-9_.-]+");
    }
}
