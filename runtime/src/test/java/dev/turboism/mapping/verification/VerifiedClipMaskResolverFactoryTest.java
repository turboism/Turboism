package dev.turboism.mapping.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The clip-mask resolver must fail closed before any unreviewed material is trusted.
 *
 * <p>There are two distinct fail-closed paths and both are exercised: a missing artifact is
 * rejected before anything is read, and an existing but unreviewed artifact is rejected by the
 * pinned manifest before the record's selectors are used. Each record case therefore pairs an
 * unreviewed-but-real artifact with the record under test, mirroring
 * {@code StatusBarVerificationManifestTest}.</p>
 */
class VerifiedClipMaskResolverFactoryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final VerifiedClipMaskResolverFactory factory = new VerifiedClipMaskResolverFactory();

    @Test
    void rejectsMissingArtifactBeforeReadingAnything() {
        Path reviewed = repositoryPath("cubism-ref/verification/cubism-5.3.02-clipmask.json");

        assertThrows(NoSuchFileException.class, () -> factory.create(
            reviewed,
            Path.of("missing-host-artifact.jar"),
            getClass().getClassLoader()
        ));
    }

    @Test
    void rejectsSelfIssuedRecordBeforeUsingItsSelectors() throws Exception {
        Path reviewed = repositoryPath("cubism-ref/verification/cubism-5.3.02-clipmask.json");
        Path selfIssued = Files.createTempFile("clipmask-self-issued", ".json");
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(reviewed.toFile());
        root.put("verifiedBy", "self-issued-test");
        mapper.writeValue(selfIssued.toFile(), root);

        assertThrows(IllegalArgumentException.class, () -> factory.create(
            selfIssued,
            unreviewedArtifact(),
            getClass().getClassLoader()
        ));
    }

    @Test
    void rejectsReviewedRecordFromAnotherAdapterSlice() throws Exception {
        Path projectRecord = repositoryPath(
            "cubism-ref/verification/cubism-5.3.02-project-workspace.json"
        );

        assertThrows(IllegalArgumentException.class, () -> factory.create(
            projectRecord,
            unreviewedArtifact(),
            getClass().getClassLoader()
        ));
    }

    @Test
    void rejectsInvalidRecord() throws Exception {
        Path invalid = Files.createTempFile("clipmask-invalid", ".json");
        Files.writeString(invalid, "{\"format\":\"turboism.static.verification.record\"}");

        assertThrows(IllegalArgumentException.class, () -> factory.create(
            invalid,
            unreviewedArtifact(),
            getClass().getClassLoader()
        ));
    }

    private static Path unreviewedArtifact() throws java.io.IOException {
        Path artifact = Files.createTempFile("clipmask-foreign", ".jar");
        Files.writeString(artifact, "not-the-reviewed-cubism-jar");
        return artifact;
    }

    private static Path repositoryPath(final String relative) {
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root != null && !Files.exists(root.resolve("settings.gradle.kts"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("repository root not found");
        }
        return root.resolve(relative);
    }
}
