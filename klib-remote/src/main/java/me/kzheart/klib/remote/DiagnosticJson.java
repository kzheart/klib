package me.kzheart.klib.remote;

import java.util.Iterator;
import java.util.Map;

final class DiagnosticJson {
    private DiagnosticJson() {
    }

    static String write(Object value) {
        StringBuilder output = new StringBuilder();
        append(output, value);
        return output.toString();
    }

    static String write(Object value, int maxUtf8Bytes) {
        if (maxUtf8Bytes < 1) throw new IllegalArgumentException("maxUtf8Bytes must be positive");
        BoundedWriter output = new BoundedWriter(maxUtf8Bytes);
        output.append(value);
        return output.result();
    }

    private static void append(StringBuilder output, Object value) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof Double || value instanceof Float) {
            // JSON 无法表示 NaN 和无穷大。
            double number = ((Number) value).doubleValue();
            output.append(Double.isNaN(number) || Double.isInfinite(number) ? "null" : value);
        } else if (value instanceof Boolean || value instanceof Number) {
            output.append(value);
        } else if (value instanceof Map<?, ?>) {
            appendMap(output, (Map<?, ?>) value);
        } else if (value instanceof Iterable<?>) {
            appendIterable(output, (Iterable<?>) value);
        } else {
            appendString(output, String.valueOf(value));
        }
    }

    private static void appendMap(StringBuilder output, Map<?, ?> values) {
        output.append('{');
        Iterator<? extends Map.Entry<?, ?>> entries = values.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<?, ?> entry = entries.next();
            appendString(output, String.valueOf(entry.getKey()));
            output.append(':');
            append(output, entry.getValue());
            if (entries.hasNext()) {
                output.append(',');
            }
        }
        output.append('}');
    }

    private static void appendIterable(StringBuilder output, Iterable<?> values) {
        output.append('[');
        Iterator<?> elements = values.iterator();
        while (elements.hasNext()) {
            append(output, elements.next());
            if (elements.hasNext()) {
                output.append(',');
            }
        }
        output.append(']');
    }

    private static void appendString(StringBuilder output, String value) {
        JsonStrings.appendQuoted(output, value);
    }

    private static final class BoundedWriter {
        private static final char[] HEX = "0123456789abcdef".toCharArray();
        private final int maxBytes;
        private final StringBuilder output = new StringBuilder();
        private int bytes;

        private BoundedWriter(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        private String result() { return output.toString(); }

        private void append(Object value) {
            if (value == null) {
                raw("null");
            } else if (value instanceof Double || value instanceof Float) {
                double number = ((Number) value).doubleValue();
                raw(Double.isNaN(number) || Double.isInfinite(number)
                        ? "null" : String.valueOf(value));
            } else if (value instanceof Boolean || value instanceof Number) {
                raw(String.valueOf(value));
            } else if (value instanceof Map<?, ?>) {
                map((Map<?, ?>) value);
            } else if (value instanceof Iterable<?>) {
                iterable((Iterable<?>) value);
            } else {
                quoted(String.valueOf(value));
            }
        }

        private void map(Map<?, ?> values) {
            ascii('{');
            Iterator<? extends Map.Entry<?, ?>> entries = values.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<?, ?> entry = entries.next();
                quoted(String.valueOf(entry.getKey()));
                ascii(':');
                append(entry.getValue());
                if (entries.hasNext()) ascii(',');
            }
            ascii('}');
        }

        private void iterable(Iterable<?> values) {
            ascii('[');
            Iterator<?> elements = values.iterator();
            while (elements.hasNext()) {
                append(elements.next());
                if (elements.hasNext()) ascii(',');
            }
            ascii(']');
        }

        private void quoted(String value) {
            ascii('"');
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                switch (current) {
                    case '"': raw("\\\""); break;
                    case '\\': raw("\\\\"); break;
                    case '\b': raw("\\b"); break;
                    case '\f': raw("\\f"); break;
                    case '\n': raw("\\n"); break;
                    case '\r': raw("\\r"); break;
                    case '\t': raw("\\t"); break;
                    default:
                        if (current < 0x20) {
                            ensure(6);
                            output.append('\\').append('u').append('0').append('0')
                                    .append(HEX[(current >>> 4) & 0xf])
                                    .append(HEX[current & 0xf]);
                            bytes += 6;
                        } else if (Character.isHighSurrogate(current)
                                && index + 1 < value.length()
                                && Character.isLowSurrogate(value.charAt(index + 1))) {
                            ensure(4);
                            output.append(current).append(value.charAt(++index));
                            bytes += 4;
                        } else {
                            int encodedBytes = current <= 0x7f ? 1
                                    : current <= 0x7ff ? 2 : 3;
                            ensure(encodedBytes);
                            output.append(current);
                            bytes += encodedBytes;
                        }
                }
            }
            ascii('"');
        }

        private void raw(String value) {
            for (int index = 0; index < value.length(); index++) ascii(value.charAt(index));
        }

        private void ascii(char value) {
            ensure(1);
            output.append(value);
            bytes++;
        }

        private void ensure(int additional) {
            if (bytes > maxBytes - additional) {
                throw new IllegalArgumentException("JSON exceeds configured UTF-8 byte limit");
            }
        }
    }
}
