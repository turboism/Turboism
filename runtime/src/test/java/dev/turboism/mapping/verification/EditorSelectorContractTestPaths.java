package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared repository and external-evidence paths for exact Editor selector contract tests. */
final class EditorSelectorContractTestPaths {

    private static final Path PROJECT_ROOT = locateProjectRoot();
    private static Path legacyEvidence;

    private EditorSelectorContractTestPaths() {}

    static Path projectRoot() {
        return PROJECT_ROOT;
    }

    static synchronized Path legacyEvidence() {
        if (legacyEvidence == null) {
            legacyEvidence = locateLegacyEvidence();
        }
        return legacyEvidence;
    }

    /**
     * Resolves the external evidence directory, or {@code null} when none is provisioned.
     * An explicitly configured but missing directory still fails loudly.
     */
    static synchronized Path resolveLegacyEvidence() {
        try {
            return legacyEvidence();
        } catch (final IllegalStateException unavailable) {
            final String configured = System.getenv("TURBOISM_LEGACY_CUBISM_REF");
            if (configured != null && !configured.isBlank()) {
                throw unavailable;
            }
            return null;
        }
    }

    /** Finds exact Editor evidence in either the current or historical reference layout. */
    static Path artifact(final String version) {
        for (final String variable : new String[] {"TURBOISM_CUBISM_REF", "TURBOISM_LEGACY_CUBISM_REF"}) {
            final String configured = System.getenv(variable);
            if (configured == null || configured.isBlank()) continue;
            final Path artifact =
                    artifactUnder(Path.of(configured).toAbsolutePath().normalize(), version);
            if (artifact != null) return artifact;
            throw new IllegalStateException(
                    "configured exact Cubism evidence is unavailable: " + variable + " " + version);
        }
        final Path local = artifactUnder(PROJECT_ROOT.resolve("cubism-ref"), version);
        if (local != null) return local;
        try {
            final Process git = new ProcessBuilder("git", "rev-parse", "--git-common-dir")
                    .directory(PROJECT_ROOT.toFile())
                    .redirectErrorStream(true)
                    .start();
            final String output = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (git.waitFor() == 0 && !output.isEmpty()) {
                final Path common = PROJECT_ROOT.resolve(output).normalize();
                final Path artifact = artifactUnder(common.getParent().resolve("cubism-ref"), version);
                if (artifact != null) return artifact;
            }
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted locating exact Cubism evidence", exception);
        } catch (final IOException exception) {
            // Source archives may not have git; retain the existing legacy resolver below.
        }
        final Path legacy = artifactUnder(legacyEvidence(), version);
        if (legacy != null) return legacy;
        throw new IllegalStateException("exact Cubism evidence is unavailable: " + version);
    }

    private static Path artifactUnder(final Path root, final String version) {
        for (final Path candidate : new Path[] {
            root.resolve(version + "/Live2D_Cubism.jar"), root.resolve("Cubism-" + version + "/jars/Live2D_Cubism.jar")
        }) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
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
            throw new IllegalStateException("configured legacy Cubism evidence directory is unavailable: " + candidate);
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
