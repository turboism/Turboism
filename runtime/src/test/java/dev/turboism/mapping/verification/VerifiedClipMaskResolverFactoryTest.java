package dev.turboism.mapping.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class VerifiedClipMaskResolverFactoryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final VerifiedClipMaskResolverFactory factory = new VerifiedClipMaskResolverFactory();

    @Test
    void rejectsMissingArtifactBeforeReadingAnything() {
        final Path reviewed = repositoryPath(
            "compatibility/cubism/verification/cubism-5.3.03-clipmask.json"
        );
        assertThrows(NoSuchFileException.class, () -> factory.create(
            reviewed, Path.of("missing-host-artifact.jar"), getClass().getClassLoader()
        ));
    }

    @Test
    void rejectsSelfIssuedPublicRecordBeforeUsingItsSelectors() throws Exception {
        final Path reviewed = repositoryPath(
            "compatibility/cubism/verification/cubism-5.3.03-clipmask.json"
        );
        final Path selfIssued = Files.createTempFile("clipmask-self-issued", ".json");
        final var root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(reviewed.toFile());
        root.put("verifiedBy", "self-issued-test");
        mapper.writeValue(selfIssued.toFile(), root);
        assertThrows(IllegalArgumentException.class, () -> factory.create(
            selfIssued, unreviewedArtifact(), getClass().getClassLoader()
        ));
    }

    @Test
    void rejectsAnotherAdapterSliceAndInvalidRecord() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> factory.create(
            repositoryPath("compatibility/cubism/verification/cubism-5.3.03-project-workspace.json"),
            unreviewedArtifact(), getClass().getClassLoader()
        ));
        final Path invalid = Files.createTempFile("clipmask-invalid", ".json");
        Files.writeString(invalid, "{\"format\":\"turboism.static.verification.record\"}");
        assertThrows(IllegalArgumentException.class, () -> factory.create(
            invalid, unreviewedArtifact(), getClass().getClassLoader()
        ));
    }

    private static Path unreviewedArtifact() throws java.io.IOException {
        final Path artifact = Files.createTempFile("clipmask-foreign", ".jar");
        Files.writeString(artifact, "not-a-reviewed-artifact");
        return artifact;
    }

    private static Path repositoryPath(final String relative) {
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root != null && !Files.exists(root.resolve("settings.gradle.kts"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("repository root not found");
        return root.resolve(relative);
    }
}
