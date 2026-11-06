package dev.turboism.compatibility.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.CubismEditor;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Offline table-driven coverage of annotation validation, expansion and version permission. */
class CubismEditorAvailabilityPolicyTest {

    @CubismEditor("5.3.02")
    interface ExactTarget {}

    @CubismEditor(value = {"5.2.03", "5.3.02"})
    interface MultiExactTarget {}

    @CubismEditor(from = "5.2.03", to = "5.3.02")
    interface InclusiveRangeTarget {}

    @CubismEditor(from = "5.2.03", exclude = "5.3.02")
    interface ExcludedTarget {}

    @CubismEditor(from = "5.2.03")
    interface OpenFromTarget {}

    @CubismEditor
    interface BareAuditTarget {}

    @CubismEditor("5.9.99")
    interface UnreviewedExactTarget {}

    @CubismEditor(value = "5.3.02", from = "5.2.03")
    interface ValueAndRangeTarget {}

    @CubismEditor({"5.3.02", "5.3.02"})
    interface DuplicateValueTarget {}

    @CubismEditor(from = "5.3.02", to = "5.2.03")
    interface ReversedRangeTarget {}

    @CubismEditor("5.3")
    interface MalformedValueTarget {}

    @CubismEditor(from = "5.3")
    interface MalformedRangeTarget {}

    private static CubismEditor declaration(final Class<?> type) {
        return type.getAnnotation(CubismEditor.class);
    }

    private static List<CubismEditor> declarations(final Class<?>... types) {
        return java.util.Arrays.stream(types)
                .map(CubismEditorAvailabilityPolicyTest::declaration)
                .toList();
    }

    @Test
    void bareAuditAnnotationImposesNoRestriction() {
        final CubismEditor beacon = declaration(BareAuditTarget.class);
        assertFalse(CubismEditorAvailabilityPolicy.isRestrictive(beacon));
        final CubismEditorAvailabilityPolicy.Resolution resolution =
                CubismEditorAvailabilityPolicy.resolve(declarations(BareAuditTarget.class), "bare");
        assertFalse(resolution.restricted());
        assertEquals(List.of(), resolution.supportedVersions());
        assertEquals(List.of(), resolution.declarations());
        assertTrue(resolution.permits("5.2.03"));
        assertTrue(resolution.permits("9.9.99"));
    }

    @Test
    void exactValueAdmitsOnlyTheNamedReviewedVersion() {
        final CubismEditorAvailabilityPolicy.Resolution resolution =
                CubismEditorAvailabilityPolicy.resolve(declarations(ExactTarget.class), "exact");

        assertTrue(resolution.restricted());
        assertEquals(List.of("5.3.02"), resolution.supportedVersions());
        assertEquals(List.of(declaration(ExactTarget.class)), resolution.declarations());
        assertTrue(resolution.permits("5.3.02"));
        assertFalse(resolution.permits("5.2.03"));
        assertFalse(resolution.permits("5.9.99"));
        assertFalse(resolution.permits(null));
        assertFalse(resolution.permits("5.3.2"));
    }

    @Test
    void multipleExactValuesKeepReviewedOrder() {
        final CubismEditorAvailabilityPolicy.Resolution resolution =
                CubismEditorAvailabilityPolicy.resolve(declarations(MultiExactTarget.class), "multi");

        assertEquals(List.of("5.2.03", "5.3.02"), resolution.supportedVersions());
        assertTrue(resolution.permits("5.2.03"));
        assertTrue(resolution.permits("5.3.02"));
        assertFalse(resolution.permits("5.3.03"));
    }

    @Test
    void inclusiveRangeCoversReviewedBoundsAndNothingOutside() {
        final CubismEditorAvailabilityPolicy.Resolution resolution =
                CubismEditorAvailabilityPolicy.resolve(declarations(InclusiveRangeTarget.class), "range");

        assertEquals(List.of("5.2.03", "5.3.00", "5.3.01", "5.3.02"), resolution.supportedVersions());
        assertTrue(resolution.permits("5.2.03"));
        assertTrue(resolution.permits("5.3.00"));
        assertTrue(resolution.permits("5.3.02"));
        assertFalse(resolution.permits("5.2.02"));
        assertFalse(resolution.permits("5.3.03"));
    }

    @Test
    void openRangeCanCoverAnUnreviewedFutureVersion() {
        final CubismEditorAvailabilityPolicy.Resolution resolution =
                CubismEditorAvailabilityPolicy.resolve(declarations(OpenFromTarget.class), "open");

        assertEquals(ReviewedReleaseTable.versions().subList(14, 20), resolution.supportedVersions());
        assertTrue(resolution.permits("5.2.03"));
        assertTrue(resolution.permits("5.3.04"));
        assertTrue(resolution.permits("5.9.99"));
        assertFalse(resolution.permits("5.2.02"));
    }

    @Test
    void exclusionAlwaysRemovesTheNamedVersion() {
        final CubismEditorAvailabilityPolicy.Resolution resolution =
                CubismEditorAvailabilityPolicy.resolve(declarations(ExcludedTarget.class), "excluded");

        assertFalse(resolution.supportedVersions().contains("5.3.02"));
        assertTrue(resolution.supportedVersions().contains("5.3.01"));
        assertTrue(resolution.supportedVersions().contains("5.3.04"));
        assertFalse(resolution.permits("5.3.02"));
        assertTrue(resolution.permits("5.3.01"));
        assertTrue(resolution.permits("5.9.99"));
    }

    @Test
    void typeAndMethodDeclarationsIntersect() {
        final CubismEditorAvailabilityPolicy.Resolution resolution = CubismEditorAvailabilityPolicy.resolve(
                declarations(InclusiveRangeTarget.class, ExactTarget.class), "intersection");

        assertEquals(List.of("5.3.02"), resolution.supportedVersions());
        assertTrue(resolution.permits("5.3.02"));
        assertFalse(resolution.permits("5.3.00"));
    }

    @Test
    void deepAdaptationTargetsKeepTheirExactAdmission() {
        assertTrue(CubismEditorAvailabilityPolicy.resolve(declarations(ExactTarget.class), "5302")
                .permits("5.3.02"));

        final CubismEditorAvailabilityPolicy.Resolution adaptation5203 =
                CubismEditorAvailabilityPolicy.resolve(declarations(ExactTarget5203.class), "5203");
        assertTrue(adaptation5203.permits("5.2.03"));
        assertFalse(adaptation5203.permits("5.3.02"));
    }

    @CubismEditor("5.2.03")
    interface ExactTarget5203 {}

    @Test
    void malformedDeclarationsFailClosed() {
        for (final Class<?> type : List.of(
                UnreviewedExactTarget.class,
                ValueAndRangeTarget.class,
                DuplicateValueTarget.class,
                ReversedRangeTarget.class,
                MalformedValueTarget.class,
                MalformedRangeTarget.class)) {
            assertThrows(
                    IllegalStateException.class,
                    () -> CubismEditorAvailabilityPolicy.resolve(declarations(type), type.getSimpleName()),
                    type.getSimpleName());
        }
    }

    @Test
    void nullDeclarationOrIdentityFailsClosed() {
        assertThrows(
                NullPointerException.class, () -> CubismEditorAvailabilityPolicy.resolve(null, "null-declarations"));
        assertThrows(
                NullPointerException.class,
                () -> CubismEditorAvailabilityPolicy.resolve(
                        java.util.Arrays.asList((CubismEditor) null), "null-entry"));
        assertThrows(
                NullPointerException.class,
                () -> CubismEditorAvailabilityPolicy.resolve(declarations(ExactTarget.class), null));
    }
}
