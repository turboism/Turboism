package dev.turboism.plugin.mcp;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Small dependency-free strict JSON codec used only by the MCP transport. */
final class Json {

    private Json() {
    }

    static Object parse(final byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0) {
            throw new IllegalArgumentException("JSON body is empty");
        }
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef
            && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf) {
            throw new IllegalArgumentException("UTF-8 BOM is not allowed");
        }
        final String input;
        try {
            final CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
            input = decoded.toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("JSON body must be valid UTF-8", failure);
        }
        return new Parser(input).parse();
    }

    static byte[] bytes(final Object value) {
        return stringify(value).getBytes(StandardCharsets.UTF_8);
    }

    static String stringify(final Object value) {
        final StringBuilder output = new StringBuilder();
        write(value, output, 0);
        return output.toString();
    }

    private static void write(final Object value, final StringBuilder output, final int depth) {
        if (depth > 64) {
            throw new IllegalArgumentException("JSON nesting exceeds 64 levels");
        }
        if (value == null) {
            output.append("null");
        } else if (value instanceof String text) {
            writeString(text, output);
        } else if (value instanceof Boolean flag) {
            output.append(flag.booleanValue());
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer
            || value instanceof Long || value instanceof BigDecimal) {
            output.append(value);
        } else if (value instanceof Float number) {
            if (!Float.isFinite(number)) {
                throw new IllegalArgumentException("JSON number must be finite");
            }
            output.append(number);
        } else if (value instanceof Double number) {
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("JSON number must be finite");
            }
            output.append(number);
        } else if (value instanceof Map<?, ?> map) {
            output.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("JSON object keys must be strings");
                }
                if (!first) output.append(',');
                first = false;
                writeString(key, output);
                output.append(':');
                write(entry.getValue(), output, depth + 1);
            }
            output.append('}');
        } else if (value instanceof Iterable<?> values) {
            output.append('[');
            boolean first = true;
            for (Object item : values) {
                if (!first) output.append(',');
                first = false;
                write(item, output, depth + 1);
            }
            output.append(']');
        } else if (value instanceof Object[] values) {
            output.append('[');
            for (int index = 0; index < values.length; index++) {
                if (index > 0) output.append(',');
                write(values[index], output, depth + 1);
            }
            output.append(']');
        } else {
            throw new IllegalArgumentException(
                "Unsupported JSON value type: " + value.getClass().getName()
            );
        }
    }

    private static void writeString(final String value, final StringBuilder output) {
        output.append('"');
        for (int offset = 0; offset < value.length();) {
            final int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            switch (codePoint) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (codePoint < 0x20) {
                        output.append(String.format(java.util.Locale.ROOT, "\\u%04x", codePoint));
                    } else {
                        output.appendCodePoint(codePoint);
                    }
                }
            }
        }
        output.append('"');
    }

    private static final class Parser {
        private final String input;
        private int offset;
        private int depth;

        private Parser(final String input) {
            this.input = Objects.requireNonNull(input, "input");
        }

        private Object parse() {
            skipWhitespace();
            final Object value = value();
            skipWhitespace();
            if (offset != input.length()) {
                throw error("trailing JSON content");
            }
            return value;
        }

        private Object value() {
            if (++depth > 64) throw error("JSON nesting exceeds 64 levels");
            try {
                if (offset >= input.length()) throw error("unexpected end of JSON");
                return switch (input.charAt(offset)) {
                    case '{' -> object();
                    case '[' -> array();
                    case '"' -> string();
                    case 't' -> literal("true", Boolean.TRUE);
                    case 'f' -> literal("false", Boolean.FALSE);
                    case 'n' -> literal("null", null);
                    default -> number();
                };
            } finally {
                depth--;
            }
        }

        private Map<String, Object> object() {
            expect('{');
            skipWhitespace();
            final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            if (take('}')) return result;
            while (true) {
                if (peek() != '"') throw error("JSON object key must be a string");
                final String key = string();
                if (result.containsKey(key)) throw error("duplicate JSON object key: " + key);
                skipWhitespace();
                expect(':');
                skipWhitespace();
                result.put(key, value());
                skipWhitespace();
                if (take('}')) return result;
                expect(',');
                skipWhitespace();
            }
        }

        private List<Object> array() {
            expect('[');
            skipWhitespace();
            final ArrayList<Object> result = new ArrayList<>();
            if (take(']')) return result;
            while (true) {
                result.add(value());
                skipWhitespace();
                if (take(']')) return result;
                expect(',');
                skipWhitespace();
            }
        }

        private String string() {
            expect('"');
            final StringBuilder result = new StringBuilder();
            while (offset < input.length()) {
                final char value = input.charAt(offset++);
                if (value == '"') return result.toString();
                if (value == '\\') {
                    if (offset >= input.length()) throw error("unterminated JSON escape");
                    switch (input.charAt(offset++)) {
                        case '"' -> result.append('"');
                        case '\\' -> result.append('\\');
                        case '/' -> result.append('/');
                        case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f');
                        case 'n' -> result.append('\n');
                        case 'r' -> result.append('\r');
                        case 't' -> result.append('\t');
                        case 'u' -> appendUnicodeEscape(result);
                        default -> throw error("invalid JSON escape");
                    }
                } else {
                    if (value < 0x20) throw error("unescaped control character in JSON string");
                    if (Character.isHighSurrogate(value)) {
                        if (offset >= input.length() || !Character.isLowSurrogate(input.charAt(offset))) {
                            throw error("unpaired high surrogate in JSON string");
                        }
                        result.append(value).append(input.charAt(offset++));
                    } else if (Character.isLowSurrogate(value)) {
                        throw error("unpaired low surrogate in JSON string");
                    } else {
                        result.append(value);
                    }
                }
            }
            throw error("unterminated JSON string");
        }

        private void appendUnicodeEscape(final StringBuilder result) {
            final char first = unicodeUnit();
            if (Character.isHighSurrogate(first)) {
                if (offset + 1 >= input.length() || input.charAt(offset) != '\\'
                    || input.charAt(offset + 1) != 'u') {
                    throw error("unpaired high surrogate escape");
                }
                offset += 2;
                final char second = unicodeUnit();
                if (!Character.isLowSurrogate(second)) {
                    throw error("invalid surrogate pair escape");
                }
                result.append(first).append(second);
            } else if (Character.isLowSurrogate(first)) {
                throw error("unpaired low surrogate escape");
            } else {
                result.append(first);
            }
        }

        private char unicodeUnit() {
            if (offset + 4 > input.length()) throw error("incomplete unicode escape");
            int value = 0;
            for (int index = 0; index < 4; index++) {
                final char character = input.charAt(offset++);
                final int digit = asciiHexDigit(character);
                if (digit < 0) throw error("invalid unicode escape");
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private Object number() {
            final int start = offset;
            if (take('-') && offset >= input.length()) throw error("incomplete JSON number");
            if (take('0')) {
                if (offset < input.length() && asciiDigit(input.charAt(offset))) {
                    throw error("leading zero in JSON number");
                }
            } else {
                digits();
            }
            if (take('.')) digits();
            if (take('e') || take('E')) {
                take('+');
                take('-');
                digits();
            }
            if (start == offset) throw error("invalid JSON value");
            final String token = input.substring(start, offset);
            try {
                final BigDecimal decimal = new BigDecimal(token);
                if (decimal.scale() <= 0) {
                    try {
                        return decimal.longValueExact();
                    } catch (ArithmeticException ignored) {
                        return decimal;
                    }
                }
                return decimal;
            } catch (NumberFormatException failure) {
                throw error("invalid JSON number");
            }
        }

        private void digits() {
            final int start = offset;
            while (offset < input.length() && asciiDigit(input.charAt(offset))) offset++;
            if (start == offset) throw error("JSON number requires a digit");
        }

        private static int asciiHexDigit(final char value) {
            if (value >= '0' && value <= '9') return value - '0';
            if (value >= 'a' && value <= 'f') return value - 'a' + 10;
            if (value >= 'A' && value <= 'F') return value - 'A' + 10;
            return -1;
        }

        private static boolean asciiDigit(final char value) {
            return value >= '0' && value <= '9';
        }

        private Object literal(final String expected, final Object value) {
            if (!input.startsWith(expected, offset)) throw error("invalid JSON literal");
            offset += expected.length();
            return value;
        }

        private void skipWhitespace() {
            while (offset < input.length()) {
                final char value = input.charAt(offset);
                if (value != ' ' && value != '\n' && value != '\r' && value != '\t') return;
                offset++;
            }
        }

        private char peek() {
            return offset < input.length() ? input.charAt(offset) : '\0';
        }

        private void expect(final char expected) {
            if (!take(expected)) throw error("expected '" + expected + "'");
        }

        private boolean take(final char expected) {
            if (offset < input.length() && input.charAt(offset) == expected) {
                offset++;
                return true;
            }
            return false;
        }

        private IllegalArgumentException error(final String message) {
            return new IllegalArgumentException(message + " at character " + offset);
        }
    }
}
