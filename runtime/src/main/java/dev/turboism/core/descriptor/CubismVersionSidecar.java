package dev.turboism.core.descriptor;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * Reads the optional {@code META-INF/turboism/cubism-versions.json} sidecar once.
 *
 * <p>The sidecar is the only place a plugin states which Cubism Editor versions it
 * supports. Discovery, the update gate and the installed-metadata view all read the
 * same bytes, so the shape policy lives here and every caller only decides what an
 * {@link Read.Absent} sidecar means for it. A present sidecar is never read partially: it
 * is either a complete {@link Declaration} or {@link Read.Malformed}.</p>
 */
public final class CubismVersionSidecar {

    /** Bounded read: larger sidecars are malformed rather than truncated. */
    public static final int MAX_SIDECAR_BYTES = 64 * 1024;

    /** Sidecar entry name inside the plugin archive. */
    public static final String ENTRY = "META-INF/turboism/cubism-versions.json";

    private static final String FORMAT = "turboism.plugin-cubism-versions/v1";
    private static final int MAX_VERSIONS = 256;
    private static final int MAX_VERSION_LENGTH = 32;
    private static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");

    /** Duplicate keys and trailing tokens are rejected: one document, one reading. */
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private CubismVersionSidecar() {}

    /**
     * Reads the sidecar of one plugin archive.
     *
     * <p>Absence is decided by the entry lookup: the path carries no trailing slash, so a
     * {@link JarEntry#isDirectory() directory entry} can never carry this name and there is
     * no separate directory case. Entry read failures are malformed, never absent.</p>
     *
     * @param archive plugin archive to read the sidecar from
     * @param pluginId descriptor id the sidecar's {@code plugin} field must equal
     * @return the sidecar verdict, never {@code null}
     */
    public static Read read(final JarFile archive, final String pluginId) {
        final JarEntry entry = archive.getJarEntry(ENTRY);
        if (entry == null) {
            return new Read.Absent();
        }
        try (InputStream source = archive.getInputStream(entry)) {
            return read(source.readNBytes(MAX_SIDECAR_BYTES + 1), pluginId);
        } catch (IOException unreadable) {
            return new Read.Malformed("CUBISM_SIDECAR_UNREADABLE", "entry bytes could not be read from the archive");
        }
    }

    /**
     * Reads one sidecar document. Never throws.
     *
     * <p>Strictness is decided here, once: {@code format} must be
     * {@code turboism.plugin-cubism-versions/v1}, {@code plugin} must equal the owning
     * descriptor id, {@code supportedVersions} must be a non-empty array and the optional
     * {@code fullFeatureVersions} a non-empty array of the same shape. Every version is a
     * {@code MAJOR.MINOR.PATCH} token of at most 32 characters, at most 256 versions per
     * list, no duplicates, and full-feature versions must be a subset of the supported ones.
     * Unknown top-level fields are ignored: they are additive metadata, not a shape claim.</p>
     *
     * @param bytes sidecar document bytes, or {@code null} when the archive has no such entry
     * @param pluginId descriptor id the sidecar's {@code plugin} field must equal
     * @return the sidecar verdict, never {@code null}
     */
    public static Read read(final byte[] bytes, final String pluginId) {
        if (bytes == null) {
            return new Read.Absent();
        }
        if (bytes.length > MAX_SIDECAR_BYTES) {
            return new Read.Malformed("CUBISM_SIDECAR_TOO_LARGE", "sidecar exceeds " + MAX_SIDECAR_BYTES + " bytes");
        }
        final JsonNode document;
        try {
            document = JSON.readTree(bytes);
        } catch (IOException | IllegalArgumentException invalid) {
            return new Read.Malformed("CUBISM_SIDECAR_INVALID_JSON", "sidecar is not a readable JSON document");
        }
        if (document == null || !document.isObject()) {
            return new Read.Malformed("CUBISM_SIDECAR_NOT_AN_OBJECT", "sidecar root must be a JSON object");
        }
        final JsonNode format = document.get("format");
        if (format == null || !format.isTextual() || !FORMAT.equals(format.textValue())) {
            return new Read.Malformed("CUBISM_SIDECAR_BAD_FORMAT", "format must be " + FORMAT);
        }
        final JsonNode declaredPlugin = document.get("plugin");
        if (declaredPlugin == null
                || !declaredPlugin.isTextual()
                || !Objects.equals(pluginId, declaredPlugin.textValue())) {
            return new Read.Malformed("CUBISM_SIDECAR_PLUGIN_MISMATCH", "plugin must equal the owning descriptor id");
        }
        try {
            final List<String> supported = versions(document.get("supportedVersions"), "supportedVersions");
            final Optional<List<String>> fullFeatures = document.has("fullFeatureVersions")
                    ? Optional.of(versions(document.get("fullFeatureVersions"), "fullFeatureVersions"))
                    : Optional.empty();
            if (fullFeatures.isPresent() && !supported.containsAll(fullFeatures.orElseThrow())) {
                return new Read.Malformed(
                        "CUBISM_SIDECAR_FULL_FEATURE_NOT_SUPPORTED",
                        "fullFeatureVersions must be a subset of supportedVersions");
            }
            return new Read.Declared(new Declaration(declaredPlugin.textValue(), supported, fullFeatures));
        } catch (MalformedVersion malformed) {
            return new Read.Malformed(malformed.code, malformed.getMessage());
        }
    }

    /**
     * Validates one version array, returning an immutable copy in declaration order.
     *
     * @param node the array node, possibly {@code null}
     * @param field field name used in diagnostics
     * @return immutable versions
     * @throws MalformedVersion when the array is missing, empty, oversized or holds a bad token
     */
    private static List<String> versions(final JsonNode node, final String field) {
        if (node == null || !node.isArray() || node.size() == 0) {
            throw new MalformedVersion("CUBISM_SIDECAR_MISSING_VERSIONS", field + " must be a non-empty array");
        }
        if (node.size() > MAX_VERSIONS) {
            throw new MalformedVersion(
                    "CUBISM_SIDECAR_TOO_MANY_VERSIONS", field + " declares more than " + MAX_VERSIONS + " versions");
        }
        final List<String> versions = new ArrayList<>(node.size());
        final Set<String> seen = new HashSet<>();
        for (int index = 0; index < node.size(); index++) {
            final JsonNode item = node.get(index);
            final String version = item != null && item.isTextual() ? item.textValue() : null;
            if (version == null
                    || version.length() > MAX_VERSION_LENGTH
                    || !VERSION.matcher(version).matches()) {
                throw new MalformedVersion(
                        "CUBISM_SIDECAR_BAD_VERSION", field + "[" + index + "] is not a MAJOR.MINOR.PATCH version");
            }
            if (!seen.add(version)) {
                throw new MalformedVersion(
                        "CUBISM_SIDECAR_DUPLICATE_VERSION", field + "[" + index + "] repeats a declared version");
            }
            versions.add(version);
        }
        return List.copyOf(versions);
    }

    /** Presence and verdict of one sidecar. */
    public sealed interface Read {
        /** Sidecar present and fully valid. */
        record Declared(Declaration declaration) implements Read {}

        /** No sidecar entry: undeclared, which is not malformed. */
        record Absent() implements Read {}

        /**
         * Sidecar present but not a valid declaration; the declaration is discarded whole.
         *
         * @param code stable diagnostic identifier
         * @param detail human-readable reason naming the violating field
         */
        record Malformed(String code, String detail) implements Read {
            public Malformed {
                Objects.requireNonNull(code, "code");
                Objects.requireNonNull(detail, "detail");
            }
        }
    }

    /**
     * One complete Cubism support declaration.
     *
     * @param pluginId identity that must match the descriptor beside it
     * @param supportedVersions exact versions with available plugin functionality
     * @param fullFeatureVersions exact versions with all features, or empty when not declared
     */
    public record Declaration(
            String pluginId, List<String> supportedVersions, Optional<List<String>> fullFeatureVersions) {
        public Declaration {
            Objects.requireNonNull(pluginId, "pluginId");
            supportedVersions = List.copyOf(supportedVersions);
            fullFeatureVersions = fullFeatureVersions.map(List::copyOf);
        }
    }

    /** Internal rejection carrying the diagnostic to report; never escapes this class. */
    private static final class MalformedVersion extends RuntimeException {
        private final String code;

        MalformedVersion(final String code, final String detail) {
            super(detail, null, false, false);
            this.code = code;
        }
    }
}
