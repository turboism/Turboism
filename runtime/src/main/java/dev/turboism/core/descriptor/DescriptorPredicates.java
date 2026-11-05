package dev.turboism.core.descriptor;

import dev.turboism.core.version.VersionRange;
import dev.turboism.sdk.permission.PermissionIds;
import java.net.URI;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure value predicates and the closed vocabularies one plugin manifest field may use.
 *
 * <p>Each predicate answers a single question about a field value and nothing else, so the tier
 * table stays the only place that decides which field is admitted, required, and reported with
 * which code.</p>
 */
final class DescriptorPredicates {

    /** Upper bound on declared classification tags. */
    static final int MAX_TAGS = 12;

    /** Shortest admitted kebab-case token. */
    static final int MIN_TOKEN_LENGTH = 2;

    /** Longest admitted kebab-case token. */
    static final int MAX_TOKEN_LENGTH = 32;

    /** UI surfaces a plugin may require from the host. */
    static final Set<String> ALLOWED_ENV_UI = Set.of("none", "swing", "embedded");

    /** Declared dependency kinds. */
    static final Set<String> ALLOWED_DEPENDENCY_TYPES = Set.of("required", "optional");

    /** Declared load-order constraints. */
    static final Set<String> ALLOWED_DEPENDENCY_ORDERINGS = Set.of("none", "before", "after");

    /** Permission scopes a declaration may request. */
    static final Set<String> ALLOWED_PERMISSION_SCOPES = Set.of("application", "user");

    /** Single source of truth: the SDK export. Adding an id there admits it here. */
    static final Set<String> KNOWN_PERMISSION_IDS = PermissionIds.KNOWN_IDS;

    private static final Pattern KEBAB_TOKEN = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Pattern EVENT_ID = Pattern.compile("^[a-z][a-z0-9]*(?:[.-][a-z][a-z0-9-]*)*$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern CONTRACT_ARTIFACT =
            Pattern.compile("^META-INF/turboism/contracts/[A-Za-z0-9][A-Za-z0-9._-]*\\.jar$");
    private static final Pattern PLUGIN_ID = Pattern.compile("^[a-z][a-z0-9-]*(?:\\.[a-z][a-z0-9-]*)+$");
    private static final Pattern SEMANTIC_VERSION =
            Pattern.compile("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$");
    private static final Pattern LOCALE_ID = Pattern.compile("^[A-Za-z]{2,8}(?:[-_][A-Za-z0-9]{1,8})*$");
    private static final String RESERVED_RESOURCE_PREFIX = "META-INF/turboism/";

    private DescriptorPredicates() {}

    /**
     * @param value candidate token
     * @return true when the value is a bounded lowercase kebab-case token
     */
    static boolean isKebabToken(final String value) {
        return value != null
                && value.length() >= MIN_TOKEN_LENGTH
                && value.length() <= MAX_TOKEN_LENGTH
                && KEBAB_TOKEN.matcher(value).matches();
    }

    /**
     * @param value dotted Java class name
     * @return true when the value is a Java binary name
     */
    static boolean isJavaBinaryName(final String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (String part : value.split("\\.", -1)) {
            if (part.isEmpty()) {
                return false;
            }
            int offset = 0;
            int point = part.codePointAt(offset);
            if (!Character.isJavaIdentifierStart(point)) {
                return false;
            }
            offset += Character.charCount(point);
            while (offset < part.length()) {
                point = part.codePointAt(offset);
                if (!Character.isJavaIdentifierPart(point)) {
                    return false;
                }
                offset += Character.charCount(point);
            }
        }
        return true;
    }

    /**
     * @param value declared resource root
     * @return true for a normalized relative directory prefix outside the reserved metadata tree
     */
    static boolean isResourceRoot(final String value) {
        return isNormalizedResourcePath(value, true)
                && !value.regionMatches(true, 0, RESERVED_RESOURCE_PREFIX, 0, RESERVED_RESOURCE_PREFIX.length());
    }

    /**
     * @param value declared message-bundle base name
     * @return true for a normalized relative base name without an extension
     */
    static boolean isResourceBaseName(final String value) {
        return isNormalizedResourcePath(value, false) && !value.endsWith(".properties") && !value.endsWith("/");
    }

    /**
     * @param value catalog identifier
     * @return true when the value is {@code base} or a well-formed locale tag
     */
    static boolean isLocaleId(final String value) {
        return "base".equals(value)
                || (value != null && LOCALE_ID.matcher(value).matches());
    }

    /**
     * @param id candidate plugin identifier
     * @return true for a reverse-domain plugin id
     */
    static boolean isValidPluginId(final String id) {
        return id != null && PLUGIN_ID.matcher(id).matches();
    }

    /**
     * @param version candidate {@code MAJOR.MINOR.PATCH} version
     * @return true when every component is a canonical decimal number
     */
    static boolean isValidVersion(final String version) {
        if (version == null || !SEMANTIC_VERSION.matcher(version).matches()) {
            return false;
        }
        try {
            for (String part : version.split("\\.")) {
                Integer.parseInt(part);
            }
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    /**
     * @param value candidate public event id
     * @return true for a stable lowercase dotted or dashed event id
     */
    static boolean isEventId(final String value) {
        return value != null && EVENT_ID.matcher(value).matches();
    }

    /**
     * @param value candidate digest
     * @return true for 64 lowercase hexadecimal characters
     */
    static boolean isSha256(final String value) {
        return value != null && SHA256.matcher(value).matches();
    }

    /**
     * @param value candidate contract artifact entry name
     * @return true for a JAR entry directly under {@code META-INF/turboism/contracts/}
     */
    static boolean isContractArtifact(final String value) {
        return value != null && CONTRACT_ARTIFACT.matcher(value).matches();
    }

    /**
     * @param value candidate version range
     * @return the failure message when the range does not parse, empty when it does
     */
    static Optional<String> versionRangeFailure(final String value) {
        if (value == null) {
            return Optional.of("invalid range");
        }
        try {
            VersionRange.parse(value);
            return Optional.empty();
        } catch (IllegalArgumentException failure) {
            return Optional.ofNullable(failure.getMessage()).or(() -> Optional.of("invalid range"));
        }
    }

    /**
     * @param value declared website
     * @return the failure message when the value is not an absolute HTTP(S) URL, empty when it is
     */
    static Optional<String> websiteFailure(final String value) {
        try {
            final URI uri = URI.create(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("website must be an absolute HTTP(S) URL");
            }
            return Optional.empty();
        } catch (IllegalArgumentException failure) {
            return Optional.ofNullable(failure.getMessage()).or(() -> Optional.of("invalid URL"));
        }
    }

    private static boolean isNormalizedResourcePath(final String value, final boolean directory) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.contains("\\")) {
            return false;
        }
        if (directory != value.endsWith("/")) {
            return false;
        }
        final String[] parts = value.split("/", -1);
        for (int index = 0; index < parts.length; index++) {
            final String part = parts[index];
            if (part.isEmpty() && index == parts.length - 1 && directory) {
                continue;
            }
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                return false;
            }
        }
        return true;
    }
}
