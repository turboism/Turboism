package dev.turboism.adapter.cubism.editor;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EditorReviewedGenerationTest {
    private static final String SLICE = "adapter.editor-model.readwrite";
    private static final Set<String> CAPABILITIES = Set.of("cubism.editor-model.read");
    private static final List<StaticSelector> SELECTORS =
            List.of(StaticSelector.classSelector("cubism.editor-model.model.class", "java/lang/String"));

    @Test
    void admitsEveryReviewedGenerationWithoutRestatingTheVersionTable() {
        assertEquals(20, ReviewedHostArtifacts.reviewedCubismVersions().size());
        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            assertTrue(EditorReviewedGeneration.admits(exact(version)), version);
        }
    }

    @Test
    void refusesUnreviewedAndCompatibilityBoundHosts() {
        assertFalse(EditorReviewedGeneration.admits(exact("5.4.00")));
        for (final String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            assertFalse(EditorReviewedGeneration.admits(compatible(version)), version);
        }
        assertThrows(NullPointerException.class, () -> EditorReviewedGeneration.admits(null));
    }

    private static VerifiedMemberResolver exact(final String version) {
        return TestVerifiedResolvers.create(
                version, SLICE, CAPABILITIES, SELECTORS, EditorReviewedGenerationTest.class.getClassLoader());
    }

    private static VerifiedMemberResolver compatible(final String version) {
        return TestVerifiedResolvers.createCompatible(
                version, "5.9.99", SLICE, CAPABILITIES, SELECTORS, EditorReviewedGenerationTest.class.getClassLoader());
    }
}
