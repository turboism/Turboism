package dev.turboism.mapping.verification;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

final class VerifiedAutoBackupResolverFactory5303Test {

    @Test
    void exact5303FactoryFailsClosedBeforeUsingUnreviewedArtifactMaterial() throws Exception {
        final VerifiedAutoBackupResolverFactory factory = new VerifiedAutoBackupResolverFactory();
        final Path record = repositoryPath(
            "compatibility/cubism/verification/cubism-5.3.03-autobackup.json"
        );

        assertThrows(java.nio.file.NoSuchFileException.class, () -> factory.create(
            record,
            Path.of("missing-cubism-5.3.03.jar"),
            getClass().getClassLoader()
        ));

        final Path foreign = Files.createTempFile("autobackup-5303-foreign", ".jar");
        Files.writeString(foreign, "not the reviewed exact 5.3.03 artifact");
        assertThrows(IllegalArgumentException.class, () -> factory.create(
            record,
            foreign,
            getClass().getClassLoader()
        ));
    }

    private static Path repositoryPath(final String relative) {
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle.kts"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("project root is unavailable");
        }
        return root.resolve(relative);
    }
}
