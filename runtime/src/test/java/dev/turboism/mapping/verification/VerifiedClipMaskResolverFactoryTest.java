package dev.turboism.mapping.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class VerifiedClipMaskResolverFactoryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final VerifiedClipMaskResolverFactory factory = new VerifiedClipMaskResolverFactory();

    @Test
    void rejectsSelfIssuedRecordBeforeUsingItsSelectors() throws Exception {
        Path reviewed = repositoryPath("docs/migration/verification/static/cubism-5.3.02-clipmask.json");
        Path selfIssued = Files.createTempFile("clipmask-self-issued", ".json");
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(reviewed.toFile());
        root.put("verifiedBy", "self-issued-test");
        mapper.writeValue(selfIssued.toFile(), root);

        assertThrows(IllegalArgumentException.class, () -> factory.create(
            selfIssued,
            Path.of("missing-host-artifact.jar"),
            getClass().getClassLoader()
        ));
    }

    @Test
    void rejectsReviewedRecordFromAnotherAdapterSlice() {
        Path projectRecord = repositoryPath(
            "docs/migration/verification/static/cubism-5.3.02-project-workspace.json"
        );

        assertThrows(IllegalArgumentException.class, () -> factory.create(
            projectRecord,
            Path.of("missing-host-artifact.jar"),
            getClass().getClassLoader()
        ));
    }

    @Test
    void rejectsInvalidRecord() throws Exception {
        Path invalid = Files.createTempFile("clipmask-invalid", ".json");
        Files.writeString(invalid, "{\"format\":\"turboism.static.verification.record\"}");

        assertThrows(IllegalArgumentException.class, () -> factory.create(
            invalid,
            Path.of("missing-host-artifact.jar"),
            getClass().getClassLoader()
        ));
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
