package dev.turboism.config;

import dev.turboism.sdk.config.ConfigCodec;
import dev.turboism.sdk.config.ConfigKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runtime-only implementation of the four frozen typed-config codecs. */
final class TypedConfigCodecSupport {

    private static final Pattern INTEGER = Pattern.compile("0|-?[1-9][0-9]*");
    private static final Pattern INTEGER_CODEC = Pattern.compile("int:(-?[0-9]+):(-?[0-9]+)");
    private static final Pattern LIST_CODEC = Pattern.compile("string-list:([0-9]+):([1-9][0-9]*)");

    private TypedConfigCodecSupport() {
    }

    static boolean isRecognized(final ConfigCodec<?> codec) {
        if (codec == null || codec.typeId() == null) {
            return false;
        }
        final String id = codec.typeId();
        return id.equals("boolean")
            || INTEGER_CODEC.matcher(id).matches()
            || id.startsWith("enum:") && id.length() > "enum:".length()
            || LIST_CODEC.matcher(id).matches();
    }

    static boolean isValidDefault(final ConfigKey<?> key) {
        return key != null && encode(key, key.defaultValue()).isPresent();
    }

    static Optional<String> encode(final ConfigKey<?> key, final Object value) {
        Objects.requireNonNull(key, "key");
        final String id = key.codec() == null ? "" : key.codec().typeId();
        if (id.equals("boolean")) {
            return value instanceof Boolean booleanValue
                ? Optional.of(booleanValue.toString())
                : Optional.empty();
        }
        final Matcher integerCodec = INTEGER_CODEC.matcher(id);
        if (integerCodec.matches()) {
            if (!(value instanceof Integer integerValue)) {
                return Optional.empty();
            }
            final int minimum = Integer.parseInt(integerCodec.group(1));
            final int maximum = Integer.parseInt(integerCodec.group(2));
            return integerValue >= minimum && integerValue <= maximum
                ? Optional.of(Integer.toString(integerValue))
                : Optional.empty();
        }
        if (id.startsWith("enum:")) {
            if (!(key.defaultValue() instanceof Enum<?> defaultEnum)
                || !(value instanceof Enum<?> enumValue)
                || enumValue.getDeclaringClass() != defaultEnum.getDeclaringClass()
                || !id.equals("enum:" + defaultEnum.getDeclaringClass().getName())) {
                return Optional.empty();
            }
            return Optional.of(enumValue.name());
        }
        final Matcher listCodec = LIST_CODEC.matcher(id);
        if (listCodec.matches() && value instanceof List<?> raw) {
            final int maximumEntries = Integer.parseInt(listCodec.group(1));
            final int maximumLength = Integer.parseInt(listCodec.group(2));
            if (raw.size() > maximumEntries || raw.stream().anyMatch(item ->
                !(item instanceof String text)
                    || text.length() > maximumLength
                    || containsUnpairedSurrogate(text))) {
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            final List<String> strings = (List<String>) raw;
            return Optional.of(encodeStringList(strings));
        }
        return Optional.empty();
    }

    static Optional<Object> decode(final ConfigKey<?> key, final String encoded) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(encoded, "encoded");
        final String id = key.codec() == null ? "" : key.codec().typeId();
        if (id.equals("boolean")) {
            return switch (encoded) {
                case "true" -> Optional.of(Boolean.TRUE);
                case "false" -> Optional.of(Boolean.FALSE);
                default -> Optional.empty();
            };
        }
        final Matcher integerCodec = INTEGER_CODEC.matcher(id);
        if (integerCodec.matches()) {
            if (!INTEGER.matcher(encoded).matches()) {
                return Optional.empty();
            }
            try {
                final int value = Integer.parseInt(encoded);
                final int minimum = Integer.parseInt(integerCodec.group(1));
                final int maximum = Integer.parseInt(integerCodec.group(2));
                return value >= minimum && value <= maximum
                    ? Optional.of(value)
                    : Optional.empty();
            } catch (NumberFormatException exception) {
                return Optional.empty();
            }
        }
        if (id.startsWith("enum:") && key.defaultValue() instanceof Enum<?> defaultEnum) {
            if (!id.equals("enum:" + defaultEnum.getDeclaringClass().getName())) {
                return Optional.empty();
            }
            for (Object constant : defaultEnum.getDeclaringClass().getEnumConstants()) {
                final Enum<?> enumValue = (Enum<?>) constant;
                if (enumValue.name().equals(encoded)) {
                    return Optional.of(enumValue);
                }
            }
            return Optional.empty();
        }
        final Matcher listCodec = LIST_CODEC.matcher(id);
        if (listCodec.matches()) {
            final int maximumEntries = Integer.parseInt(listCodec.group(1));
            final int maximumLength = Integer.parseInt(listCodec.group(2));
            return decodeStringList(encoded, maximumEntries, maximumLength)
                .map(value -> (Object) value);
        }
        return Optional.empty();
    }

    static Object immutableDefault(final ConfigKey<?> key) {
        final Object value = key.defaultValue();
        return value instanceof List<?> list ? List.copyOf(list) : value;
    }

    private static String encodeStringList(final List<String> values) {
        final StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                result.append(',');
            }
            result.append('"');
            appendJsonString(result, values.get(index));
            result.append('"');
        }
        return result.append(']').toString();
    }

    private static void appendJsonString(final StringBuilder output, final String value) {
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20) {
                        output.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
                }
            }
        }
    }

    private static Optional<List<String>> decodeStringList(
        final String encoded,
        final int maximumEntries,
        final int maximumLength
    ) {
        try {
            final JsonStringListParser parser = new JsonStringListParser(encoded);
            final List<String> values = parser.parse();
            if (values.size() > maximumEntries
                || values.stream().anyMatch(value ->
                    value.length() > maximumLength || containsUnpairedSurrogate(value))
                || !encodeStringList(values).equals(encoded)) {
                return Optional.empty();
            }
            return Optional.of(List.copyOf(values));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static boolean containsUnpairedSurrogate(final String value) {
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                    || !Character.isLowSurrogate(value.charAt(++index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(character)) {
                return true;
            }
        }
        return false;
    }

    private static final class JsonStringListParser {
        private final String text;
        private int index;

        private JsonStringListParser(final String text) {
            this.text = text;
        }

        private List<String> parse() {
            expect('[');
            final List<String> result = new ArrayList<>();
            if (peek(']')) {
                index++;
                finish();
                return result;
            }
            while (true) {
                result.add(parseString());
                if (peek(']')) {
                    index++;
                    finish();
                    return result;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            final StringBuilder value = new StringBuilder();
            while (index < text.length()) {
                final char character = text.charAt(index++);
                if (character == '"') {
                    return value.toString();
                }
                if (character < 0x20) {
                    throw invalid();
                }
                if (character != '\\') {
                    value.append(character);
                    continue;
                }
                if (index >= text.length()) {
                    throw invalid();
                }
                final char escape = text.charAt(index++);
                switch (escape) {
                    case '"', '\\' -> value.append(escape);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> value.append(parseControlEscape());
                    default -> throw invalid();
                }
            }
            throw invalid();
        }

        private char parseControlEscape() {
            if (index + 4 > text.length()) {
                throw invalid();
            }
            final String hex = text.substring(index, index + 4);
            index += 4;
            if (!hex.matches("00[0-9a-f]{2}")) {
                throw invalid();
            }
            final char value = (char) Integer.parseInt(hex, 16);
            if (value >= 0x20 || value == '\b' || value == '\f'
                || value == '\n' || value == '\r' || value == '\t') {
                throw invalid();
            }
            return value;
        }

        private void expect(final char expected) {
            if (index >= text.length() || text.charAt(index++) != expected) {
                throw invalid();
            }
        }

        private boolean peek(final char expected) {
            return index < text.length() && text.charAt(index) == expected;
        }

        private void finish() {
            if (index != text.length()) {
                throw invalid();
            }
        }

        private static IllegalArgumentException invalid() {
            return new IllegalArgumentException("noncanonical string-list config value");
        }
    }
}
