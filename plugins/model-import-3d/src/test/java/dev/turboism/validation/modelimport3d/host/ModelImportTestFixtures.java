package dev.turboism.validation.modelimport3d.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;

/**
 * Resolves and gates 3D model test assets (committed small fixtures under
 * {@code test-assets/model-import-3d/}, or local large fixtures under {@code artifacts/} and
 * {@code build/model-import-3d-inputs/}).
 *
 * <p>When fixtures are not present in the workspace, tests are cleanly skipped via
 * {@link Assumptions#assumeTrue(boolean, String)} unless fixture presence is strictly
 * enforced via {@code -Pturboism.requireImport3dFixtures=true} or the environment variable
 * {@code TURBOISM_REQUIRE_IMPORT_3D_FIXTURES=true}.
 */
final class ModelImportTestFixtures {

    private static final String COMMITTED_FIXTURES_DIR = "test-assets/model-import-3d";

    private static final String PROPERTY_REQUIRE_FIXTURES = "turboism.requireImport3dFixtures";
    private static final String ENV_REQUIRE_FIXTURES = "TURBOISM_REQUIRE_IMPORT_3D_FIXTURES";

    private static final String PROPERTY_ARTIFACTS = "turboism.import3d.artifacts";
    private static final String ENV_ARTIFACTS = "TURBOISM_IMPORT_3D_ARTIFACTS";

    private static final String PROPERTY_FIXTURES = "turboism.import3d.fixtures";
    private static final String ENV_FIXTURES = "TURBOISM_IMPORT_3D_FIXTURES";

    private static final String PROPERTY_INPUTS = "turboism.import3d.inputs";
    private static final String ENV_INPUTS = "TURBOISM_IMPORT_3D_INPUTS";

    private static final String PROPERTY_REPO = "mi3d.repo";
    private static final String ENV_REPO = "TURBOISM_REPO_ROOT";

    private ModelImportTestFixtures() {}

    /**
     * Indicates whether missing fixtures should cause test failure instead of being skipped.
     */
    static boolean isFixturesRequired() {
        final String prop = System.getProperty(PROPERTY_REQUIRE_FIXTURES);
        if (prop != null && !prop.isBlank()) {
            return Boolean.parseBoolean(prop);
        }
        final String env = System.getenv(ENV_REQUIRE_FIXTURES);
        if (env != null && !env.isBlank()) {
            return Boolean.parseBoolean(env);
        }
        return false;
    }

    /**
     * Resolves the repository root directory.
     */
    static Path repoRoot() {
        final String prop = System.getProperty(PROPERTY_REPO);
        if (prop != null && !prop.isBlank()) {
            final Path p = Path.of(prop).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        final String env = System.getenv(ENV_REPO);
        if (env != null && !env.isBlank()) {
            final Path p = Path.of(env).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current != null) {
            return current;
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    /**
     * Resolves a relative fixture path against committed {@code test-assets/model-import-3d/}
     * first, then repository root or override directories.
     */
    static Path resolveFixture(final String relativePath) {
        final Path root = repoRoot();
        final Path normalizedRelative = Path.of(relativePath).normalize();
        final Path committedRoot = root.resolve(COMMITTED_FIXTURES_DIR).normalize();

        // 0. Check committed small fixtures under test-assets/model-import-3d/ first
        if (relativePath.startsWith("artifacts/") || relativePath.startsWith("artifacts\\")) {
            final String subPath = relativePath.substring("artifacts/".length());
            final Path committedCandidate = committedRoot.resolve(subPath).normalize();
            if (committedCandidate.startsWith(committedRoot) && Files.isRegularFile(committedCandidate)) {
                return committedCandidate;
            }
        }
        if (relativePath.contains("model-import-3d-inputs")) {
            final int idx = relativePath.indexOf("model-import-3d-inputs");
            final String subPath = relativePath.substring(idx + "model-import-3d-inputs".length() + 1);
            final Path committedCandidate = committedRoot.resolve(subPath).normalize();
            if (committedCandidate.startsWith(committedRoot) && Files.isRegularFile(committedCandidate)) {
                return committedCandidate;
            }
        }
        final Path directCommittedCandidate =
                committedRoot.resolve(normalizedRelative).normalize();
        if (directCommittedCandidate.startsWith(committedRoot) && Files.isRegularFile(directCommittedCandidate)) {
            return directCommittedCandidate;
        }

        // 1. Direct resolution against repo root (standard layout or symlink)
        final Path repoCandidate = root.resolve(normalizedRelative).normalize();
        if (Files.exists(repoCandidate)) {
            return repoCandidate;
        }

        // 2. Check general fixtures directory override (TURBOISM_IMPORT_3D_FIXTURES)
        for (final String val : new String[] {System.getProperty(PROPERTY_FIXTURES), System.getenv(ENV_FIXTURES)}) {
            if (val != null && !val.isBlank()) {
                final Path dir = Path.of(val).toAbsolutePath().normalize();
                final Path candidate = dir.resolve(normalizedRelative).normalize();
                if (Files.exists(candidate)) {
                    return candidate;
                }
            }
        }

        // 3. Check artifacts override (TURBOISM_IMPORT_3D_ARTIFACTS) when path is under artifacts/
        if (relativePath.startsWith("artifacts/") || relativePath.startsWith("artifacts\\")) {
            final String subPath = relativePath.substring("artifacts/".length());
            for (final String val :
                    new String[] {System.getProperty(PROPERTY_ARTIFACTS), System.getenv(ENV_ARTIFACTS)}) {
                if (val != null && !val.isBlank()) {
                    final Path dir = Path.of(val).toAbsolutePath().normalize();
                    Path candidate = dir.resolve(subPath).normalize();
                    if (Files.exists(candidate)) {
                        return candidate;
                    }
                    candidate = dir.resolve(normalizedRelative).normalize();
                    if (Files.exists(candidate)) {
                        return candidate;
                    }
                }
            }
        }

        // 4. Check inputs override (TURBOISM_IMPORT_3D_INPUTS) when path is under build/model-import-3d-inputs/
        if (relativePath.contains("model-import-3d-inputs")) {
            final int idx = relativePath.indexOf("model-import-3d-inputs");
            final String subPath = relativePath.substring(idx + "model-import-3d-inputs".length() + 1);
            for (final String val : new String[] {System.getProperty(PROPERTY_INPUTS), System.getenv(ENV_INPUTS)}) {
                if (val != null && !val.isBlank()) {
                    final Path dir = Path.of(val).toAbsolutePath().normalize();
                    Path candidate = dir.resolve(subPath).normalize();
                    if (Files.exists(candidate)) {
                        return candidate;
                    }
                    candidate = dir.resolve(normalizedRelative).normalize();
                    if (Files.exists(candidate)) {
                        return candidate;
                    }
                }
            }
        }

        // Default to repo candidate even if missing
        return repoCandidate;
    }

    /**
     * Asserts that a fixture exists, or skips the test using {@link Assumptions#assumeTrue}
     * when absent (or fails if fixture requirement is enabled).
     *
     * @param relativePath repository-relative fixture path
     * @return resolved path to the existing fixture
     */
    static Path fixture(final String relativePath) {
        final Path path = resolveFixture(relativePath);
        if (!Files.isRegularFile(path)) {
            final String message = "3D model test fixture is not staged: " + relativePath
                    + " (resolved at " + path + "). "
                    + "To run this test, stage the required assets under artifacts/ or build/model-import-3d-inputs/ "
                    + "(or set TURBOISM_IMPORT_3D_ARTIFACTS / TURBOISM_IMPORT_3D_FIXTURES, or symlink artifacts/ into worktree). "
                    + "See validation/model-import-3d-assets/README.md and plugins/model-import-3d/README.md. "
                    + "To fail on missing fixtures instead of skipping, pass -Pturboism.requireImport3dFixtures=true "
                    + "or set TURBOISM_REQUIRE_IMPORT_3D_FIXTURES=true.";
            if (isFixturesRequired()) {
                Assertions.fail(message);
            }
            Assumptions.assumeTrue(false, message);
        }
        return path;
    }

    /**
     * Reads all bytes of the required fixture, gating via {@link #fixture(String)}.
     *
     * @param relativePath repository-relative fixture path
     * @return contents of the fixture file
     * @throws IOException if reading fails
     */
    static byte[] readBytes(final String relativePath) throws IOException {
        return Files.readAllBytes(fixture(relativePath));
    }
}
