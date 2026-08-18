package me.kzheart.klib.remote;

/** 共用的 JSON 字符串转义与反转义工具。 */
final class JsonStrings {
    private JsonStrings() {
    }

    static void appendQuoted(StringBuilder output, String value) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    output.append("\\\"");
                    break;
                case '\\':
                    output.append("\\\\");
                    break;
                case '\b':
                    output.append("\\b");
                    break;
                case '\f':
                    output.append("\\f");
                    break;
                case '\n':
                    output.append("\\n");
                    break;
                case '\r':
                    output.append("\\r");
                    break;
                case '\t':
                    output.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        output.append(String.format(
                                java.util.Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
            }
        }
        output.append('"');
    }

    static String unescape(String value) throws RemoteProtocolException {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '\\') {
                result.append(character);
                continue;
            }
            if (++index >= value.length()) {
                throw new RemoteProtocolException("Invalid JSON string escape");
            }
            char escaped = value.charAt(index);
            switch (escaped) {
                case '"':
                case '\\':
                case '/':
                    result.append(escaped);
                    break;
                case 'b':
                    result.append('\b');
                    break;
                case 'f':
                    result.append('\f');
                    break;
                case 'n':
                    result.append('\n');
                    break;
                case 'r':
                    result.append('\r');
                    break;
                case 't':
                    result.append('\t');
                    break;
                case 'u':
                    // 代理对以两个连续的 \\u 转义到达，追加两半后会自然重新组合。
                    result.append(parseUnicodeEscape(value, index + 1));
                    index += 4;
                    break;
                default:
                    throw new RemoteProtocolException(
                            "Unsupported JSON escape in collector response");
            }
        }
        return result.toString();
    }

    private static char parseUnicodeEscape(String value, int start)
            throws RemoteProtocolException {
        if (start + 4 > value.length()) {
            throw new RemoteProtocolException("Truncated JSON unicode escape");
        }
        int code = 0;
        for (int index = start; index < start + 4; index++) {
            int digit = Character.digit(value.charAt(index), 16);
            if (digit < 0) {
                throw new RemoteProtocolException("Invalid JSON unicode escape");
            }
            code = (code << 4) | digit;
        }
        return (char) code;
    }
}
