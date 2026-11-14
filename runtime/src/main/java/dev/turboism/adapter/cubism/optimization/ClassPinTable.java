package dev.turboism.adapter.cubism.optimization;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads reviewed target-class tables from JSON classpath resources.
 *
 * <p>Each table names, per reviewed Cubism Editor version, the host classes a hook
 * rewrites or reads. The whole-artifact identity in {@code ReviewedHostArtifacts} plus the
 * per-hook method-shape gates admit the bytes; this table only scopes which classes a hook
 * may touch, so a hook never inspects a class outside its reviewed contract.</p>
 *
 * <p>Two on-disk shapes carry the same facts. The compact shape states one shared class
 * list plus per-version deviations, so a newly reviewed version inherits {@code default}
 * with no edit:</p>
 *
 * <pre>{@code
 * { "default": ["com/live2d/..."], "overrides": { "5.3.04": ["com/live2d/..."] } }
 * }</pre>
 *
 * <p>The legacy flat shape ({@code { "5.0.00": [ ... ] }}) stays readable because
 * synthetic fixtures and older branches still use it. Both shapes resolve to the same
 * immutable {@code Map<version, List<className>>}.</p>
 *
 * <p>The JSON is parsed with a minimal hand-written reader — no external JSON library is
 * required — that accepts exactly the subset this format uses: a two-level nested object
 * whose leaves are JSON arrays of strings, plus the compact {@code default} array.
 * Malformed input fails closed with an {@link IllegalStateException}.</p>
 *
 * <p>Every hook loads its table during static initialization, so the parsed result is
 * cached per table name: one resource read and one parse per table for the whole JVM
 * lifetime. The cache holds only immutable maps, so a shared entry cannot be mutated.</p>
 */
public final class ClassPinTable {

    private static final String RESOURCE_PREFIX = "dev/turboism/adapter/cubism/class-pins/";
    private static final String RESOURCE_SUFFIX = ".json";

    /** Compact-shape key naming the class list every version inherits. */
    private static final String DEFAULT_KEY = "default";

    /** Compact-shape key naming the versions that deviate from {@link #DEFAULT_KEY}. */
    private static final String OVERRIDES_KEY = "overrides";

    /** Parsed tables keyed by resource base name; values are deeply immutable. */
    private static final Map<String, Map<String, List<String>>> CACHE = new ConcurrentHashMap<>();

    private ClassPinTable() {}

    /**
     * Loads a target-class table from a classpath JSON resource.
     *
     * <p>The result is cached per table name, so repeated calls return the same immutable
     * map without reading the resource again.</p>
     *
     * @param name the resource base name without path prefix or extension, e.g. {@code "mesh-mirror"}
     * @return an immutable {@code Map<version, List<className>>} in reviewed-version order
     * @throws IllegalStateException if the resource is missing or malformed
     */
    public static Map<String, List<String>> load(final String name) {
        Objects.requireNonNull(name, "name");
        final Map<String, List<String>> cached = CACHE.get(name);
        if (cached != null) {
            return cached;
        }
        final Map<String, List<String>> parsed = parse(name);
        final Map<String, List<String>> previous = CACHE.putIfAbsent(name, parsed);
        return previous != null ? previous : parsed;
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

    private static Map<String, List<String>> parse(final String name) {
        final String path = RESOURCE_PREFIX + name + RESOURCE_SUFFIX;
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
            return parseTable(json, path);
        } catch (IOException failure) {
            throw new IllegalStateException("class pin table could not be loaded: " + path, failure);
        }
    }

    // ---- minimal JSON parser for the compact and legacy table shapes ----

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

    /**
     * Parses one table document and resolves it to version to classes.
     *
     * <p>A document that names {@code default} or {@code overrides} is compact; every other
     * document is legacy flat and its keys are version labels.</p>
     */
    private static Map<String, List<String>> parseTable(final String json, final String source) {
        final Document document = parseDocument(json, source);
        if (document.defaultClasses() == null && document.overrides() == null) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(document.versions()));
        }
        if (document.defaultClasses() == null) {
            throw new IllegalStateException("compact class pin table lacks '" + DEFAULT_KEY + "' in " + source);
        }
        if (!document.versions().isEmpty()) {
            throw new IllegalStateException(
                    "compact class pin table mixes '" + DEFAULT_KEY + "' with version keys in " + source);
        }
        return resolveCompact(document, source);
    }

    /** Expands {@code default} plus {@code overrides} across every reviewed version. */
    private static Map<String, List<String>> resolveCompact(final Document document, final String source) {
        final List<String> defaults = document.defaultClasses();
        final Map<String, List<String>> overrides = document.overrides() == null ? Map.of() : document.overrides();
        // Reviewed order, oldest first: the resolved map must not depend on the
        // unspecified iteration order of the reviewed version set.
        final List<String> reviewed = reviewedVersionsInOrder();
        for (final String version : overrides.keySet()) {
            if (!reviewed.contains(version)) {
                throw new IllegalStateException(
                        "class pin table override names unreviewed version '" + version + "' in " + source);
            }
            if (overrides.get(version).equals(defaults)) {
                throw new IllegalStateException(
                        "class pin table override '" + version + "' repeats '" + DEFAULT_KEY + "' in " + source);
            }
        }
        final Map<String, List<String>> resolved = new LinkedHashMap<>();
        for (final String version : reviewed) {
            final List<String> classes = overrides.get(version);
            resolved.put(version, classes != null ? classes : defaults);
        }
        return Collections.unmodifiableMap(resolved);
    }

    private static List<String> reviewedVersionsInOrder() {
        final List<String> versions = new ArrayList<>();
        for (final var artifact : ReviewedHostArtifacts.all()) {
            ReviewedHostArtifacts.cubismVersionOf(artifact).ifPresent(versions::add);
        }
        return versions;
    }

    private static Document parseDocument(final String json, final String source) {
        final int[] pos = {0};
        skipWhitespace(json, pos);
        expect(json, pos, '{', source);
        final Map<String, List<String>> versions = new LinkedHashMap<>();
        final Map<String, List<String>> overrides = new LinkedHashMap<>();
        List<String> defaultClasses = null;
        boolean sawDefault = false;
        boolean sawOverrides = false;
        skipWhitespace(json, pos);
        if (pos[0] < json.length() && json.charAt(pos[0]) == '}') {
            pos[0]++;
            return new Document(versions, null, null);
        }
        while (true) {
            skipWhitespace(json, pos);
            final String key = parseString(json, pos, source);
            skipWhitespace(json, pos);
            expect(json, pos, ':', source);
            skipWhitespace(json, pos);
            if (DEFAULT_KEY.equals(key)) {
                if (sawDefault) {
                    throw new IllegalStateException("duplicate key '" + DEFAULT_KEY + "' in " + source);
                }
                sawDefault = true;
                defaultClasses = parseClassArray(json, pos, source);
            } else if (OVERRIDES_KEY.equals(key)) {
                if (sawOverrides) {
                    throw new IllegalStateException("duplicate key '" + OVERRIDES_KEY + "' in " + source);
                }
                sawOverrides = true;
                parseOverrides(json, pos, source, overrides);
            } else {
                if (versions.containsKey(key)) {
                    throw new IllegalStateException("duplicate version key '" + key + "' in " + source);
                }
                versions.put(key, parseClassArray(json, pos, source));
            }
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
        return new Document(versions, defaultClasses, sawOverrides ? overrides : null);
    }

    private static void parseOverrides(
            final String json, final int[] pos, final String source, final Map<String, List<String>> overrides) {
        expect(json, pos, '{', source);
        skipWhitespace(json, pos);
        if (pos[0] < json.length() && json.charAt(pos[0]) == '}') {
            pos[0]++;
            return;
        }
        while (true) {
            skipWhitespace(json, pos);
            final String version = parseString(json, pos, source);
            skipWhitespace(json, pos);
            expect(json, pos, ':', source);
            skipWhitespace(json, pos);
            final List<String> classes = parseClassArray(json, pos, source);
            if (overrides.containsKey(version)) {
                throw new IllegalStateException("duplicate override key '" + version + "' in " + source);
            }
            overrides.put(version, classes);
            skipWhitespace(json, pos);
            if (pos[0] >= json.length()) {
                throw new IllegalStateException("unexpected end of JSON in " + source);
            }
            if (json.charAt(pos[0]) == '}') {
                pos[0]++;
                return;
            }
            expect(json, pos, ',', source);
        }
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

    /**
     * One parsed document before shape resolution.
     *
     * @param versions legacy flat version blocks, empty for a compact document
     * @param defaultClasses the compact {@code default} block, or {@code null} when absent
     * @param overrides the compact {@code overrides} blocks, or {@code null} when absent
     */
    private record Document(
            Map<String, List<String>> versions, List<String> defaultClasses, Map<String, List<String>> overrides) {}
}
