package dev.turboism.installer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal bounded JSON parser/serializer used only for the installer-side
 * config.json merge (frozen spec: "parsed with a bounded JSON parser").
 *
 * Bounds: the input is additionally capped at 64 KiB by the caller; nesting
 * depth and string length are capped here; syntax is strict RFC 8259. Numbers
 * are kept as Long when integral and within range, otherwise as BigDecimal,
 * so large integers and exponent numbers stay exact and can never become
 * non-finite values; serialization always emits valid JSON. Objects keep
 * insertion order (LinkedHashMap) so serialization is deterministic.
 * This class performs no evaluation and never touches the filesystem.
 */
final class BoundedJson {

    static final int MAX_DEPTH = 24;
    static final int MAX_STRING_LEN = 16 * 1024;

    static final class JsonException extends RuntimeException {
        JsonException(String message) {
            super(message);
        }
    }

    private final String text;
    private int pos;

    private BoundedJson(String text) {
        this.text = text;
    }

    /**
     * Parses the whole document; throws JsonException on any violation.
     *
     * A single leading UTF-8 BOM (U+FEFF, written by Windows PowerShell 5.1
     * {@code Set-Content -Encoding UTF8}) is stripped before parsing so every
     * config read path tolerates it; serialization never re-emits the BOM.
     * Exactly one leading BOM is tolerated: a second BOM still fails closed.
     */
    static Object parse(String text) {
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        BoundedJson parser = new BoundedJson(text);
        Object value = parser.parseValue(0);
        parser.skipWhitespace();
        if (parser.pos != text.length()) {
            throw new JsonException("unexpected trailing content at offset " + parser.pos);
        }
        return value;
    }

    /** Deterministic JSON serialization of parse() output. */
    static String serialize(Object value) {
        StringBuilder out = new StringBuilder(256);
        writeValue(out, value);
        return out.toString();
    }

    private Object parseValue(int depth) {
        if (depth > MAX_DEPTH) {
            throw new JsonException("nesting exceeds " + MAX_DEPTH + " levels");
        }
        skipWhitespace();
        if (pos >= text.length()) {
            throw new JsonException("unexpected end of input");
        }
        char c = text.charAt(pos);
        switch (c) {
            case '{':
                return parseObject(depth);
            case '[':
                return parseArray(depth);
            case '"':
                return parseString();
            case 't':
                expectLiteral("true");
                return Boolean.TRUE;
            case 'f':
                expectLiteral("false");
                return Boolean.FALSE;
            case 'n':
                expectLiteral("null");
                return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return parseNumber();
                }
                throw new JsonException("unexpected character '" + c + "' at offset " + pos);
        }
    }

    private Map<String, Object> parseObject(int depth) {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            expect(':');
            Object value = parseValue(depth + 1);
            map.put(key, value); // duplicates: last wins, matching the runtime's parser behavior
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == '}') {
                pos++;
                return map;
            } else {
                throw new JsonException("expected ',' or '}' at offset " + pos);
            }
        }
    }

    private List<Object> parseArray(int depth) {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(parseValue(depth + 1));
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == ']') {
                pos++;
                return list;
            } else {
                throw new JsonException("expected ',' or ']' at offset " + pos);
            }
        }
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder(32);
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '"') {
                pos++;
                if (sb.length() > MAX_STRING_LEN) {
                    throw new JsonException("string exceeds " + MAX_STRING_LEN + " characters");
                }
                return sb.toString();
            }
            if (c == '\\') {
                pos++;
                if (pos >= text.length()) {
                    throw new JsonException("unterminated escape at end of input");
                }
                char e = text.charAt(pos);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 >= text.length()) {
                            throw new JsonException("truncated \\u escape at offset " + pos);
                        }
                        int unit = 0;
                        for (int index = 1; index <= 4; index++) {
                            int digit = asciiHex(text.charAt(pos + index));
                            if (digit < 0) {
                                throw new JsonException("invalid \\u escape at offset " + pos);
                            }
                            unit = (unit << 4) | digit;
                        }
                        sb.append((char) unit);
                        pos += 4;
                        break;
                    default:
                        throw new JsonException("invalid escape '\\" + e + "' at offset " + pos);
                }
                pos++;
            } else if (c < 0x20) {
                throw new JsonException("unescaped control character in string at offset " + pos);
            } else {
                sb.append(c);
                pos++;
            }
        }
        throw new JsonException("unterminated string");
    }

    private Number parseNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        // integer part: RFC 8259 allows "0" or a nonzero digit followed by
        // digits only -- leading zeros ("01", "-01") are rejected here.
        if (pos >= text.length()) {
            throw new JsonException("truncated number at offset " + start);
        }
        char c = text.charAt(pos);
        if (c == '0') {
            pos++;
            if (pos < text.length() && isDigit(text.charAt(pos))) {
                throw new JsonException("invalid number: leading zero at offset " + start);
            }
        } else if (c >= '1' && c <= '9') {
            pos++;
            while (pos < text.length() && isDigit(text.charAt(pos))) {
                pos++;
            }
        } else {
            throw new JsonException("invalid number at offset " + start);
        }
        // optional fraction: '.' must be followed by at least one digit
        if (pos < text.length() && text.charAt(pos) == '.') {
            pos++;
            int fracStart = pos;
            while (pos < text.length() && isDigit(text.charAt(pos))) {
                pos++;
            }
            if (pos == fracStart) {
                throw new JsonException("invalid number: missing fraction digits at offset " + start);
            }
        }
        // optional exponent: e/E, optional sign, at least one digit
        if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
            pos++;
            if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                pos++;
            }
            int expStart = pos;
            while (pos < text.length() && isDigit(text.charAt(pos))) {
                pos++;
            }
            if (pos == expStart) {
                throw new JsonException("invalid number: missing exponent digits at offset " + start);
            }
        }
        String token = text.substring(start, pos);
        try {
            return Long.valueOf(token);
        } catch (NumberFormatException ignored) {
            // integral token outside the Long range falls through to BigDecimal
        }
        try {
            // BigDecimal keeps large integers, decimal and exponent numbers
            // exact, and can never become a non-finite Double: serializing a
            // value like 1e400 must emit "1E+400" (valid JSON), never
            // "Infinity".
            return new java.math.BigDecimal(token);
        } catch (NumberFormatException e) {
            throw new JsonException("invalid number '" + token + "' at offset " + start);
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static int asciiHex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private void expectLiteral(String literal) {
        if (!text.startsWith(literal, pos)) {
            throw new JsonException("invalid literal at offset " + pos);
        }
        pos += literal.length();
    }

    private void expect(char c) {
        if (pos >= text.length() || text.charAt(pos) != c) {
            throw new JsonException("expected '" + c + "' at offset " + pos);
        }
        pos++;
    }

    private char peek() {
        if (pos >= text.length()) {
            throw new JsonException("unexpected end of input");
        }
        return text.charAt(pos);
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private static void writeValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            writeString(out, (String) value);
        } else if (value instanceof Boolean) {
            out.append(value.toString());
        } else if (value instanceof Number) {
            out.append(value.toString());
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(out, String.valueOf(entry.getKey()));
                out.append(':');
                writeValue(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof List) {
            out.append('[');
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeValue(out, item);
            }
            out.append(']');
        } else {
            throw new JsonException("unsupported value type: " + value.getClass().getName());
        }
    }

    private static void writeString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }
}
