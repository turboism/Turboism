package dev.turboism.mapping.verification;

import java.nio.file.Files;
import java.nio.file.Path;

/** Shared repository and external-evidence paths for exact Editor selector contract tests. */
final class EditorSelectorContractTestPaths {

    private static final Path PROJECT_ROOT = locateProjectRoot();
    private static final Path LEGACY_EVIDENCE = locateLegacyEvidence();

    private EditorSelectorContractTestPaths() {
    }

    static Path projectRoot() {
        return PROJECT_ROOT;
    }

    static Path legacyEvidence() {
        return LEGACY_EVIDENCE;
    }

    private static Path locateProjectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("project root is unavailable");
        }
        return current;
    }

    private static Path locateLegacyEvidence() {
        final String configured = System.getenv("TURBOISM_LEGACY_CUBISM_REF");
        if (configured != null && !configured.isBlank()) {
            final Path candidate = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            throw new IllegalStateException(
                "configured legacy Cubism evidence directory is unavailable: " + candidate
            );
        }
        Path current = PROJECT_ROOT;
        while (current != null) {
            final Path candidate = current.resolveSibling("turboism-legacy/cubism-ref");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("legacy Cubism evidence directory is unavailable");
    }
}
