package dev.turboism.distribution;

import dev.turboism.core.archive.ArchivePathPolicy;
import dev.turboism.core.archive.ArchivePaths;
import dev.turboism.core.archive.ArchiveStructureException;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class PluginPathPolicy {
    /**
     * The strict-parser policy seam for plugin archives: same entry-name and collision
     * rules as before, surfaced through the neutral {@link ArchiveStructureException}
     * with the original {@code ARCHIVE_PATH_UNSAFE} code.
     */
    static final ArchivePathPolicy ARCHIVE = new ArchivePathPolicy() {
        @Override
        public void validateEntry(final String name, final boolean directory)
                throws ArchiveStructureException {
            try {
                PluginPathPolicy.validate(name, directory);
            } catch (final DistributionValidationException exception) {
                throw translate(exception);
            } catch (final Exception exception) {
                throw new ArchiveStructureException(
                    "ARCHIVE_PATH_UNSAFE", "Unsafe plugin archive path", name);
            }
        }

        /**
         * The outer surface keeps its historical verdict: a file too short to
         * be an archive fails the raw-size gate ({@code PACKAGE_TOO_LARGE}),
         * not the structural {@code ARCHIVE_*} family — which would be wrapped
         * as {@code ARTIFACT_JAR_INVALID} by the inspector.
         */
        @Override
        public String shortArchiveCode() {
            return "PACKAGE_TOO_LARGE";
        }

        @Override
        public void validateCollisions(final List<String> names)
                throws ArchiveStructureException {
            try {
                PluginPathPolicy.validateCollisions(names);
            } catch (final DistributionValidationException exception) {
                throw translate(exception);
            } catch (final Exception exception) {
                throw new ArchiveStructureException(
                    "ARCHIVE_PATH_UNSAFE", "Unsafe plugin archive path", "archive");
            }
        }

        private static ArchiveStructureException translate(
            final DistributionValidationException exception
        ) {
            return new ArchiveStructureException(
                exception.code(), exception.getMessage(), exception.problemPath());
        }
    };

    private PluginPathPolicy() {}

    static void validate(String path, boolean directory) throws Exception {
        String value = directory && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        require(!value.isEmpty() && Normalizer.isNormalized(value, Normalizer.Form.NFC), path);
        require(value.getBytes(StandardCharsets.UTF_8).length <= PluginArchiveLimits.PATH_BYTES_MAX, path);
        require(value.split("/", -1).length <= PluginArchiveLimits.PATH_DEPTH_MAX, path);
        require(ArchivePaths.relativePath(value), path);
    }

    static void validateCollisions(List<String> paths) throws Exception {
        String collision = ArchivePaths.pathCollision(paths);
        require(collision == null, collision == null ? "archive" : collision);
    }

    static boolean contamination(String name, boolean mainDescriptorAllowed) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.startsWith("meta-inf/versions/") || lower.endsWith(".jar")) return true;
        if (descriptor(lower)) return !(mainDescriptorAllowed && lower.equals("meta-inf/turboism/plugin.json"));
        if (runtimeNamespace(lower) || internalVariant(lower)) return true;
        if (lower.startsWith("dev/turboism/sdk/") || lower.startsWith("dev/turboism/test/")
            || lower.startsWith("dev/turboism/testframework/") || lower.startsWith("testframework/")) return true;
        if (lower.startsWith("com/live2d/") || hostPrefix(lower) || nativeSuffix(lower)) return true;
        return installer(lower);
    }

    private static boolean descriptor(String path) {
        return path.equals("meta-inf/turboism/plugin.json")
            || path.endsWith("/meta-inf/turboism/plugin.json");
    }

    private static boolean runtimeNamespace(String path) {
        return starts(path, "dev/turboism/core/", "dev/turboism/hook/", "dev/turboism/mapping/",
            "dev/turboism/adapter/", "dev/turboism/permissions/", "dev/turboism/diagnostics/",
            "dev/turboism/bootstrap/", "dev/turboism/ui/", "dev/turboism/internal/");
    }

    private static boolean internalVariant(String path) {
        return path.startsWith("dev/turboism/") && path.contains("/internal/");
    }

    private static boolean hostPrefix(String path) {
        return starts(path, "native/", "natives/", "host/", "cubism/", "live2d/");
    }

    private static boolean nativeSuffix(String path) {
        return ends(path, ".dll", ".so", ".dylib", ".exe", ".msi", ".dmg", ".pkg",
            ".app", ".deb", ".rpm");
    }

    private static boolean installer(String path) {
        String base = path;
        if (base.startsWith("scripts/")) base = base.substring("scripts/".length());
        if (!(base.equals("install") || base.equals("installer") || base.equals("setup")
            || base.startsWith("install.") || base.startsWith("installer.") || base.startsWith("setup."))) return false;
        int dot = base.indexOf('.');
        return dot < 0 || Set.of(".sh", ".bat", ".cmd", ".ps1").contains(base.substring(dot));
    }

    private static boolean starts(String value, String... prefixes) {
        for (String prefix : prefixes) if (value.startsWith(prefix)) return true;
        return false;
    }

    private static boolean ends(String value, String... suffixes) {
        for (String suffix : suffixes) if (value.endsWith(suffix)) return true;
        return false;
    }

    private static void require(boolean valid, String path) throws Exception {
        if (!valid) throw ArchivePolicy.problem("ARCHIVE_PATH_UNSAFE", "Unsafe plugin archive path", path);
    }
}
