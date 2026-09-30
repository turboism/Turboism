package dev.turboism.adapter.cubism.optimization;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Loads reviewed-class SHA-256 pin tables from JSON classpath resources.
 *
 * <p>Pin tables are stored as JSON files under {@code dev/turboism/adapter/cubism/class-pins/}
 * on the classpath. Each file maps a Cubism Editor version to a set of class-name → SHA-256
 * entries that the hook verifies before arming. Externalising these tables into resource files
 * removes hundreds of hard-coded hex literals from Java source, makes multi-branch development
 * less conflict-prone, and allows automated tooling to generate or update pin files without
 * touching compiled code.</p>
 *
 * <p>The JSON is parsed with a minimal hand-written reader — no external JSON library is
 * required — that accepts exactly the subset this format uses: a two-level nested object
 * whose leaves are JSON string values. Malformed input fails closed with an
 * {@link IllegalStateException}.</p>
 *
 * <p>The returned map is deeply immutable and preserves insertion (file) order.</p>
 */
public final class ClassPinTable {

    private static final String RESOURCE_PREFIX = "dev/turboism/adapter/cubism/class-pins/";
    private static final Pattern HEX_64 = Pattern.compile("[0-9a-f]{64}");

    private ClassPinTable() {}

    /**
     * Loads a pin table from a classpath JSON resource.
     *
     * @param name the resource base name without path prefix or extension, e.g. {@code "mesh-mirror"}
     * @return an immutable {@code Map<version, Map<className, sha256>>} in file order
     * @throws IllegalStateException if the resource is missing or malformed
     */
    public static Map<String, Map<String, String>> load(final String name) {
        Objects.requireNonNull(name, "name");
        final String path = RESOURCE_PREFIX + name + ".json";
        // The distributed agent declares Boot-Class-Path, so its classes (including this
        // one) have no defining loader; the -javaagent jar is also on the system class
        // path, which carries the same pin-table resources.
        final ClassLoader loader = ClassPinTable.class.getClassLoader();
        try (InputStream input =
                loader != null ? loader.getResourceAsStream(path) : ClassLoader.getSystemResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("class pin table resource not found: " + path);
            }
            final String json = readFully(input);
            return parseOuterObject(json, path);
        } catch (IOException failure) {
            throw new IllegalStateException("class pin table could not be loaded: " + path, failure);
        }
    }

    /**
     * Loads a pin table and returns the pinned SHA-256 for one class under one version.
     *
     * @param name the resource base name, as in {@link #load(String)}
     * @param version the Cubism Editor version key
     * @param className the class key inside the version block
     * @return the pinned lowercase hex SHA-256
     * @throws IllegalStateException if the resource is missing, malformed, or lacks the entry
     */
    public static String singleSha256(final String name, final String version, final String className) {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(className, "className");
        final Map<String, String> entries = load(name).get(version);
        if (entries == null) {
            throw new IllegalStateException("class pin table '" + name + "' has no block for version " + version);
        }
        final String sha256 = entries.get(className);
        if (sha256 == null) {
            throw new IllegalStateException(
                    "class pin table '" + name + "' version " + version + " has no entry for class " + className);
        }
        return sha256;
    }

    // ---- minimal JSON parser for { "version": { "class": "sha256" } } ----

    private static String readFully(final InputStream input) throws IOException {
        final StringBuilder buffer = new StringBuilder(8192);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            final char[] chunk = new char[4096];
            for (int read; (read = reader.read(chunk)) >= 0; ) {
                buffer.append(chunk, 0, read);
            }
        }
        return buffer.toString();
    }

    private static Map<String, Map<String, String>> parseOuterObject(final String json, final String source) {
        final int[] pos = {0};
        skipWhitespace(json, pos);
        expect(json, pos, '{', source);
        final Map<String, Map<String, String>> result = new LinkedHashMap<>();
        skipWhitespace(json, pos);
        if (pos[0] < json.length() && json.charAt(pos[0]) == '}') {
            pos[0]++;
            return Collections.unmodifiableMap(result);
        }
        while (true) {
            skipWhitespace(json, pos);
            final String version = parseString(json, pos, source);
            skipWhitespace(json, pos);
            expect(json, pos, ':', source);
            skipWhitespace(json, pos);
            final Map<String, String> inner = parseInnerObject(json, pos, source);
            if (result.containsKey(version)) {
                throw new IllegalStateException("duplicate version key '" + version + "' in " + source);
            }
            result.put(version, inner);
            skipWhitespace(json, pos);
            if (pos[0] >= json.length()) {
                throw new IllegalStateException("unexpected end of JSON in " + source);
            }
            if (json.charAt(pos[0]) == '}') {
                pos[0]++;
                break;
            }
            expect(json, pos, ',', source);
        }
        skipWhitespace(json, pos);
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, String> parseInnerObject(final String json, final int[] pos, final String source) {
        expect(json, pos, '{', source);
        final Map<String, String> entries = new LinkedHashMap<>();
        skipWhitespace(json, pos);
        if (pos[0] < json.length() && json.charAt(pos[0]) == '}') {
            pos[0]++;
            return Collections.unmodifiableMap(entries);
        }
        while (true) {
            skipWhitespace(json, pos);
            final String className = parseString(json, pos, source);
            skipWhitespace(json, pos);
            expect(json, pos, ':', source);
            skipWhitespace(json, pos);
            final String sha256 = parseString(json, pos, source);
            if (!HEX_64.matcher(sha256).matches()) {
                throw new IllegalStateException(
                        "invalid SHA-256 value for class '" + className + "' in " + source + ": " + sha256);
            }
            if (entries.containsKey(className)) {
                throw new IllegalStateException(
                        "duplicate class key '" + className + "' in version block in " + source);
            }
            entries.put(className, sha256);
            skipWhitespace(json, pos);
            if (pos[0] >= json.length()) {
                throw new IllegalStateException("unexpected end of JSON in " + source);
            }
            if (json.charAt(pos[0]) == '}') {
                pos[0]++;
                break;
            }
            expect(json, pos, ',', source);
        }
        return Collections.unmodifiableMap(entries);
    }

    private static String parseString(final String json, final int[] pos, final String source) {
        expect(json, pos, '"', source);
        final StringBuilder value = new StringBuilder();
        while (pos[0] < json.length()) {
            final char ch = json.charAt(pos[0]++);
            if (ch == '"') {
                return value.toString();
            }
            if (ch == '\\') {
                if (pos[0] >= json.length()) {
                    throw new IllegalStateException("unterminated escape in " + source);
                }
                final char escaped = json.charAt(pos[0]++);
                switch (escaped) {
                    case '"':
                    case '\\':
                    case '/':
                        value.append(escaped);
                        break;
                    case 'n':
                        value.append('\n');
                        break;
                    case 't':
                        value.append('\t');
                        break;
                    case 'r':
                        value.append('\r');
                        break;
                    default:
                        value.append('\\');
                        value.append(escaped);
                }
            } else {
                value.append(ch);
            }
        }
        throw new IllegalStateException("unterminated string in " + source);
    }

    private static void expect(final String json, final int[] pos, final char expected, final String source) {
        if (pos[0] >= json.length() || json.charAt(pos[0]) != expected) {
            final String actual = pos[0] < json.length() ? "'" + json.charAt(pos[0]) + "'" : "end of input";
            throw new IllegalStateException(
                    "expected '" + expected + "' but found " + actual + " at position " + pos[0] + " in " + source);
        }
        pos[0]++;
    }

    private static void skipWhitespace(final String json, final int[] pos) {
        while (pos[0] < json.length()) {
            final char ch = json.charAt(pos[0]);
            if (ch != ' ' && ch != '\t' && ch != '\n' && ch != '\r') break;
            pos[0]++;
        }
    }
}
