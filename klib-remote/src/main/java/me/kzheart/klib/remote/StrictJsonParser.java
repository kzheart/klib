package me.kzheart.klib.remote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只用于解析受大小限制的 Remote 响应，不接受重复字段或尾随内容。 */
final class StrictJsonParser {
    private static final int MAX_DEPTH = 32;
    private static final int MAX_NODES = 8192;
    private final String source;
    private int index;
    private int nodes;

    private StrictJsonParser(String source) {
        this.source = source;
    }

    static Object parse(String source) {
        if (source == null) throw new IllegalArgumentException("JSON must not be null");
        StrictJsonParser parser = new StrictJsonParser(source);
        Object value = parser.value(0);
        parser.whitespace();
        if (parser.index != source.length()) parser.invalid();
        return value;
    }

    private Object value(int depth) {
        whitespace();
        if (depth > MAX_DEPTH || ++nodes > MAX_NODES || index >= source.length()) invalid();
        char current = source.charAt(index);
        if (current == '{') return object(depth + 1);
        if (current == '[') return array(depth + 1);
        if (current == '"') return string();
        if (current == 't') { literal("true"); return Boolean.TRUE; }
        if (current == 'f') { literal("false"); return Boolean.FALSE; }
        if (current == 'n') { literal("null"); return null; }
        return integer();
    }

    private Map<String, Object> object(int depth) {
        index++;
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        whitespace();
        if (consume('}')) return result;
        while (true) {
            whitespace();
            if (index >= source.length() || source.charAt(index) != '"') invalid();
            String key = string();
            whitespace();
            require(':');
            Object value = value(depth);
            if (result.containsKey(key)) invalid();
            result.put(key, value);
            whitespace();
            if (consume('}')) return result;
            require(',');
        }
    }

    private List<Object> array(int depth) {
        index++;
        List<Object> result = new ArrayList<Object>();
        whitespace();
        if (consume(']')) return result;
        while (true) {
            result.add(value(depth));
            whitespace();
            if (consume(']')) return result;
            require(',');
        }
    }

    private String string() {
        require('"');
        StringBuilder result = new StringBuilder();
        while (index < source.length()) {
            char current = source.charAt(index++);
            if (current == '"') return result.toString();
            if (current == '\\') {
                if (index >= source.length()) invalid();
                char escaped = source.charAt(index++);
                switch (escaped) {
                    case '"': case '\\': case '/': result.append(escaped); break;
                    case 'b': result.append('\b'); break;
                    case 'f': result.append('\f'); break;
                    case 'n': result.append('\n'); break;
                    case 'r': result.append('\r'); break;
                    case 't': result.append('\t'); break;
                    case 'u': result.append(unicode()); break;
                    default: invalid();
                }
            } else {
                if (current < 0x20) invalid();
                result.append(current);
            }
        }
        invalid();
        return null;
    }

    private char unicode() {
        if (index > source.length() - 4) invalid();
        int value = 0;
        for (int offset = 0; offset < 4; offset++) {
            int digit = Character.digit(source.charAt(index++), 16);
            if (digit < 0) invalid();
            value = value * 16 + digit;
        }
        return (char) value;
    }

    private Long integer() {
        int start = index;
        if (consume('-') && index >= source.length()) invalid();
        if (consume('0')) {
            if (index < source.length() && Character.isDigit(source.charAt(index))) invalid();
        } else {
            if (index >= source.length() || source.charAt(index) < '1'
                    || source.charAt(index) > '9') invalid();
            while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
        }
        if (index < source.length()) {
            char trailing = source.charAt(index);
            if (trailing == '.' || trailing == 'e' || trailing == 'E') invalid();
        }
        try {
            return Long.valueOf(source.substring(start, index));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("invalid JSON");
        }
    }

    private void literal(String expected) {
        if (!source.regionMatches(index, expected, 0, expected.length())) invalid();
        index += expected.length();
    }

    private void whitespace() {
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current != ' ' && current != '\n' && current != '\r' && current != '\t') return;
            index++;
        }
    }

    private boolean consume(char expected) {
        if (index < source.length() && source.charAt(index) == expected) {
            index++;
            return true;
        }
        return false;
    }

    private void require(char expected) {
        if (!consume(expected)) invalid();
    }

    private void invalid() {
        throw new IllegalArgumentException("invalid JSON");
    }
}
