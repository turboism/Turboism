package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.test.Stubs;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Shared repository and external-evidence paths for exact Editor selector contract tests. */
public final class EditorSelectorContractTestPaths {

    /** Reason reported when this machine has no staged legacy Cubism evidence. */
    public static final String LEGACY_EVIDENCE_SKIP_REASON =
            "legacy Cubism evidence is not staged on this machine; exact-artifact verification skips";

    private static final String VERIFICATION_RECORDS = "build/generated/verification";

    /** Evidence directory and reviewed record pinned by each exact Editor host generation. */
    private static final Map<String, HostGeneration> HOST_GENERATIONS = Map.of(
            "5.2", new HostGeneration("Cubism-5.2", "cubism-5.2.03-editor-model.json"),
            "5.2.03", new HostGeneration("Cubism-5.2", "cubism-5.2.03-editor-model.json"),
            "5.3.02", new HostGeneration("Cubism-5.3.02", "cubism-5.3.02-editor-model.json"));

    private static final Path PROJECT_ROOT = locateProjectRoot();
    private static Path legacyEvidence;

    private EditorSelectorContractTestPaths() {}

    public static Path projectRoot() {
        return PROJECT_ROOT;
    }

    public static synchronized Path legacyEvidence() {
        if (legacyEvidence == null) {
            legacyEvidence = locateLegacyEvidence();
        }
        return legacyEvidence;
    }

    /**
     * Resolves the external evidence directory, or {@code null} when none is provisioned.
     * An explicitly configured but missing directory still fails loudly.
     */
    public static synchronized Path resolveLegacyEvidence() {
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
    public static Path artifact(final String version) {
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

    /** Builds the evidence classloader over every JAR beside {@code artifact}. */
    public static URLClassLoader loader(final Path artifact) {
        return Stubs.jarClassLoader(artifact);
    }

    /** Skips the calling test unless the legacy Cubism evidence root is provisioned. */
    public static void assumeLegacyEvidence() {
        assumeTrue(resolveLegacyEvidence() != null, LEGACY_EVIDENCE_SKIP_REASON);
    }

    /** Resolves the reviewed host JAR at {@code <evidenceDirectory>/jars/Live2D_Cubism.jar}. */
    public static Path legacyArtifact(final String evidenceDirectory) {
        assumeLegacyEvidence();
        return legacyEvidence().resolve(evidenceDirectory).resolve("jars/Live2D_Cubism.jar");
    }

    /** Resolves a generated verification record by file name. */
    public static Path verificationRecord(final String recordName) {
        return PROJECT_ROOT.resolve(VERIFICATION_RECORDS).resolve(recordName);
    }

    /**
     * Creates the exact Editor-model resolver for a pinned host generation
     * ({@code 5.2}, {@code 5.2.03} or {@code 5.3.02}); skips when evidence is absent.
     */
    public static VerifiedMemberResolver editorModelResolver(final String version) throws IOException {
        final HostGeneration generation = HOST_GENERATIONS.get(version);
        if (generation == null) {
            throw new IllegalArgumentException("unreviewed exact Editor host generation: " + version);
        }
        return editorModelResolver(generation.recordName(), generation.evidenceDirectory());
    }

    /** Creates the exact Editor-model resolver for an explicit record/evidence override. */
    public static VerifiedMemberResolver editorModelResolver(final String recordName, final String evidenceDirectory)
            throws IOException {
        final Path artifact = legacyArtifact(evidenceDirectory);
        return SliceRegistry.EDITOR_MODEL
                .resolverFactory()
                .create(verificationRecord(recordName), artifact, loader(artifact));
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
        return Stubs.locateProjectRoot();
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

    /** Evidence directory and generated record name for one reviewed Editor host generation. */
    private record HostGeneration(String evidenceDirectory, String recordName) {}
}
