package dev.turboism.ui.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.mapping.verification.StaticSelector;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class SceneTableHostProfileTest {

    @Test
    void admitsOnlyReviewedArtifactsWithStaticSceneEvidence() {
        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            final Optional<HostArtifactDigest> artifact = reviewedArtifactFor(version);
            assertTrue(artifact.isPresent(), "no reviewed artifact for " + version);
            assertEquals(
                    version,
                    SceneTableHostProfile.forArtifact(artifact.orElseThrow())
                            .orElseThrow()
                            .cubismVersion());
        }
        assertFalse(SceneTableHostProfile.forArtifact(new HostArtifactDigest(1L, "0".repeat(64)))
                .isPresent());
    }

    private static Optional<HostArtifactDigest> reviewedArtifactFor(final String version) {
        return java.util.Arrays.stream(ReviewedHostArtifacts.class.getFields())
                .filter(field -> field.getName().matches("CUBISM_\\d+_\\d+_\\d+"))
                .map(field -> {
                    try {
                        return (HostArtifactDigest) field.get(null);
                    } catch (IllegalAccessException failure) {
                        throw new IllegalStateException(failure);
                    }
                })
                .filter(digest -> ReviewedHostArtifacts.cubismVersionOf(digest)
                        .map(version::equals)
                        .orElse(false))
                .findFirst();
    }

    @Test
    void pinsEveryUsedMemberByOwnerNameAndDescriptor() {
        final SceneTableHostProfile profile = SceneTableHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_3_02)
                .orElseThrow();
        final Map<String, StaticSelector> selectors =
                profile.selectors().stream().collect(Collectors.toMap(StaticSelector::alias, Function.identity()));

        assertEquals(25, selectors.size());
        assertSelector(
                selectors.get(SceneTableHostProfile.LISTENER_PALETTE),
                "com/live2d/cubism/view/palette/scene/m",
                "a",
                "Lcom/live2d/cubism/view/palette/scene/b;");
        assertSelector(
                selectors.get(SceneTableHostProfile.CONTROLLER_TABLE_DATA),
                "com/live2d/cubism/view/palette/scene/b",
                "h",
                "Ljava/util/ArrayList;");
        assertSelector(
                selectors.get(SceneTableHostProfile.CONTROLLER_SELECT_ROW),
                "com/live2d/cubism/view/palette/scene/b",
                "b",
                "(I)V");
        assertSelector(
                selectors.get(SceneTableHostProfile.CONTROLLER_SELECT_ROW_FALLBACK),
                "com/live2d/cubism/view/palette/scene/b",
                "a",
                "(I)V");
        assertSelector(
                selectors.get(SceneTableHostProfile.DOCUMENT_SWITCH_SCENE_DEFAULT),
                "com/live2d/cubism/doc/animation/CSceneDocument",
                "switchScene$default",
                "(Lcom/live2d/cubism/doc/animation/CSceneDocument;Lcom/live2d/util/a/a;ILjava/lang/Object;)V");
        assertTrue(profile.selectors().stream()
                .filter(selector -> selector.kind() != StaticSelector.Kind.CLASS)
                .allMatch(selector -> !selector.ownerInternalName().isBlank()
                        && !selector.memberName().isBlank()
                        && !selector.descriptor().isBlank()));
    }

    @Test
    void rejectsMalformedProfileDefinitions() {
        final HostArtifactDigest artifact = new HostArtifactDigest(0L, "0".repeat(64));
        final StaticSelector selector = StaticSelector.classSelector("fixture", "example/Fixture");
        assertThrows(
                IllegalArgumentException.class,
                () -> new SceneTableHostProfile(" ", artifact, java.util.List.of(selector)));
        assertThrows(
                IllegalArgumentException.class, () -> new SceneTableHostProfile("test", artifact, java.util.List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SceneTableHostProfile("test", artifact, java.util.List.of(selector, selector)));
    }

    private static void assertSelector(
            final StaticSelector selector, final String owner, final String name, final String descriptor) {
        assertEquals(owner, selector.ownerInternalName());
        assertEquals(name, selector.memberName());
        assertEquals(descriptor, selector.descriptor());
    }
}
