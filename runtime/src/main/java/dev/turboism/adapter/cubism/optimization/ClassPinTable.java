package dev.turboism.adapter.cubism.optimization;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Loads reviewed target-class tables from JSON classpath resources.
 *
 * <p>Each table names, per reviewed Cubism Editor version, the host classes a hook
 * rewrites or reads. The whole-artifact identity in {@code ReviewedHostArtifacts} plus the
 * per-hook method-shape gates admit the bytes; this table only scopes which classes a hook
 * may touch, so a hook never inspects a class outside its reviewed contract.</p>
 *
 * <p>The JSON is parsed with a minimal hand-written reader — no external JSON library is
 * required — that accepts exactly the subset this format uses: a two-level nested object
 * whose leaves are JSON arrays of strings. Malformed input fails closed with an
 * {@link IllegalStateException}.</p>
 *
 * <p>The returned map is deeply immutable and preserves insertion (file) order.</p>
 */
public final class ClassPinTable {

    private static final String RESOURCE_PREFIX = "dev/turboism/adapter/cubism/class-pins/";

    private ClassPinTable() {}

    /**
     * Loads a target-class table from a classpath JSON resource.
     *
     * @param name the resource base name without path prefix or extension, e.g. {@code "mesh-mirror"}
     * @return an immutable {@code Map<version, List<className>>} in file order
     * @throws IllegalStateException if the resource is missing or malformed
     */
    public static Map<String, List<String>> load(final String name) {
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
     * Loads a table and returns the target classes one version declares.
     *
     * @param name the resource base name, as in {@link #load(String)}
     * @param version the Cubism Editor version key
     * @return the declared internal names, in file order
     * @throws IllegalStateException if the resource is missing, malformed, or lacks the version
     */
    public static List<String> classes(final String name, final String version) {
        Objects.requireNonNull(version, "version");
        final List<String> classes = load(name).get(version);
        if (classes == null) {
            throw new IllegalStateException("class pin table '" + name + "' has no block for version " + version);
        }
        return classes;
    }

    // ---- minimal JSON parser for { "version": [ "class", ... ] } ----

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

    private static Map<String, List<String>> parseOuterObject(final String json, final String source) {
        final int[] pos = {0};
        skipWhitespace(json, pos);
        expect(json, pos, '{', source);
        final Map<String, List<String>> result = new LinkedHashMap<>();
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
            final List<String> classes = parseClassArray(json, pos, source);
            if (result.containsKey(version)) {
                throw new IllegalStateException("duplicate version key '" + version + "' in " + source);
            }
            result.put(version, classes);
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

    private static List<String> parseClassArray(final String json, final int[] pos, final String source) {
        expect(json, pos, '[', source);
        final List<String> classes = new ArrayList<>();
        skipWhitespace(json, pos);
        if (pos[0] < json.length() && json.charAt(pos[0]) == ']') {
            pos[0]++;
            return List.copyOf(classes);
        }
        while (true) {
            skipWhitespace(json, pos);
            final String className = parseString(json, pos, source);
            if (className.isBlank()) {
                throw new IllegalStateException("blank class name in " + source);
            }
            if (classes.contains(className)) {
                throw new IllegalStateException("duplicate class key '" + className + "' in " + source);
            }
            classes.add(className);
            skipWhitespace(json, pos);
            if (pos[0] >= json.length()) {
                throw new IllegalStateException("unexpected end of JSON in " + source);
            }
            if (json.charAt(pos[0]) == ']') {
                pos[0]++;
                break;
            }
            expect(json, pos, ',', source);
        }
        return List.copyOf(classes);
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
