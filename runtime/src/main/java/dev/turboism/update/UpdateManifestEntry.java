package dev.turboism.update;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A single framework-owned payload file in an update manifest.
 *
 * <p>Only explicit Windows package root files and Graal library paths are eligible. This is a path boundary, not proof of local
 * ownership. All plugins use the independent catalog updater and are excluded here. Existing
 * eligible files require trusted installation evidence in the planner.</p>
 */
public record UpdateManifestEntry(String relativePath, long bytes, String sha256, String contentId) {

    public static final long MAX_ENTRY_BYTES = 512L * 1024L * 1024L;

    private static final Pattern SHA256_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CONTENT_ID_PATTERN = Pattern.compile("sha256:[0-9a-f]{64}");

    // Exact Windows standard-package locations; existing files still require authenticated receipts.
    static final Set<String> FRAMEWORK_ROOT_FILES = Set.of(
            "EULA.en.txt",
            "EULA.ja.txt",
            "EULA.ko.txt",
            "EULA.zh-Hans.txt",
            "LICENSE.txt",
            "README.ja.txt",
            "README.ko.txt",
            "README.txt",
            "README.zh.txt",
            "configure_turboism.ps1",
            "cubism-launch-common.ps1",
            "install-jar-payload.ps1",
            "install-managed-graal.ps1",
            "install-script-engine.ps1",
            "launch-cubism-turboism.bat",
            "launch-cubism-turboism.ps1",
            "managed-graal.json",
            "retired-plugins.txt",
            "script-engine.json",
            "turboism-agent.jar",
            "turboism.ico",
            "turboism.png");

    private static final Pattern GRAAL_LIB_PATTERN = Pattern.compile("graal/lib/[A-Za-z0-9._-]+\\.jar");

    private static final Set<String> WINDOWS_RESERVED_DEVICE_NAMES = Set.of(
            "con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9", "lpt1",
            "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    public UpdateManifestEntry {
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(contentId, "contentId");

        if (relativePath.length() > 240
                || relativePath.contains("\\")
                || relativePath.contains("..")
                || relativePath.startsWith("/")) {
            throw new IllegalArgumentException("unnormalized or escaping relativePath: " + relativePath);
        }

        validateEligiblePath(relativePath);
        validateNoWindowsReservedNames(relativePath);

        if (bytes < 0 || bytes > MAX_ENTRY_BYTES) {
            throw new IllegalArgumentException("entry bytes out of bounds: " + bytes);
        }
        if (!SHA256_PATTERN.matcher(sha256).matches()) {
            throw new IllegalArgumentException("SHA-256 must be 64 lowercase hex characters: " + sha256);
        }
        if (!CONTENT_ID_PATTERN.matcher(contentId).matches()) {
            throw new IllegalArgumentException("contentId must be sha256:<64 hex>: " + contentId);
        }
        if (!contentId.equals("sha256:" + sha256)) {
            throw new IllegalArgumentException("contentId digest does not match entry sha256");
        }
    }

    private static void validateEligiblePath(final String relativePath) {
        if (FRAMEWORK_ROOT_FILES.contains(relativePath)) {
            return;
        }
        if (GRAAL_LIB_PATTERN.matcher(relativePath).matches()) {
            return;
        }
        throw new IllegalArgumentException(
                "FRAMEWORK_OWNERSHIP_VIOLATION: path is outside this slice: " + relativePath);
    }

    private static void validateNoWindowsReservedNames(final String relativePath) {
        final String[] segments = relativePath.split("/");
        for (final String segment : segments) {
            if (segment.isEmpty()) continue;
            final int dotIndex = segment.indexOf('.');
            final String baseName = (dotIndex >= 0 ? segment.substring(0, dotIndex) : segment).toLowerCase(Locale.ROOT);
            if (WINDOWS_RESERVED_DEVICE_NAMES.contains(baseName)) {
                throw new IllegalArgumentException("path segment contains Windows reserved device name: " + segment);
            }
        }
    }
}
