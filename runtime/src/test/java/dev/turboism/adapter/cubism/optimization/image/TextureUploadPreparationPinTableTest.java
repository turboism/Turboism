package dev.turboism.adapter.cubism.optimization.image;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.adapter.cubism.optimization.ClassPinTable;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Offline coverage contract for the texture-upload factory owner table. */
class TextureUploadPreparationPinTableTest {
    private static final Map<String, Map<String, String>> TABLE = ClassPinTable.load("texture-upload-preparation");

    @Test
    void pinTableCoversExactlyTheReviewedStableVersions() {
        final Set<String> reviewed = ReviewedHostArtifacts.all().stream()
                .map(ReviewedHostArtifacts::cubismVersionOf)
                .map(version -> version.orElseThrow())
                .collect(Collectors.toUnmodifiableSet());
        assertEquals(reviewed, TABLE.keySet());
    }

    @Test
    void everyBlockNamesOneFactoryAndAgreesWithTheTransformer() {
        for (final var entry : TABLE.entrySet()) {
            assertEquals(1, entry.getValue().size(), entry.getKey() + " must pin exactly one texture factory class");
            final String owner = entry.getValue().keySet().iterator().next();
            assertTrue(
                    owner.matches("com/live2d/graphics3d/shader/[A-Za-z_$][A-Za-z0-9_$]*"),
                    entry.getKey() + " owner outside the reviewed package: " + owner);
            assertEquals(
                    owner,
                    TextureUploadPreparationTransformer.ownerForVersion(entry.getKey()),
                    entry.getKey() + " owner must derive from its pin block");
        }
    }

    @Test
    void shaderFamilyTracksTheReviewedGenerationBoundaries() {
        final Map<String, String> expectedFamilies = Map.of(
                "5.0", "com/live2d/graphics3d/shader/w",
                "5.1", "com/live2d/graphics3d/shader/w",
                "5.2", "com/live2d/graphics3d/shader/y",
                "5.3", "com/live2d/graphics3d/shader/A");
        for (final var entry : TABLE.entrySet()) {
            final String generation = entry.getKey().substring(0, 3);
            assertEquals(
                    expectedFamilies.get(generation),
                    entry.getValue().keySet().iterator().next(),
                    entry.getKey() + " changed factory generation");
        }
    }

    @Test
    void pinnedClassesRemainStablePerFamily() {
        final Set<String> families =
                TABLE.keySet().stream().map(version -> version.substring(0, 3)).collect(Collectors.toUnmodifiableSet());
        assertEquals(Set.of("5.0", "5.1", "5.2", "5.3"), families);
        for (final String family : families) {
            final Set<String> hashes = TABLE.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(family + "."))
                    .map(entry -> entry.getValue().values().iterator().next())
                    .collect(Collectors.toUnmodifiableSet());
            assertEquals(1, hashes.size(), family + " unexpectedly split its factory class pin");
        }
    }

    @Test
    void reviewedArtifactsRemainTheOnlyAdmittedVersions() {
        for (final HostArtifactDigest digest : ReviewedHostArtifacts.all()) {
            final String version = ReviewedHostArtifacts.cubismVersionOf(digest).orElseThrow();
            assertTrue(TABLE.containsKey(version), version + " reviewed but absent from the pin table");
        }
    }
}
