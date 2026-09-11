package dev.turboism.tests.plugin;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsHistoryManagerValidationProbeTest {

    @Test
    void escapesEvidenceAsJsonLines() {
        assertEquals(
            "quote\\\" slash\\\\ line\\n tab\\t",
            WindowsHistoryManagerValidationProbe.json("quote\" slash\\ line\n tab\t")
        );
    }

    @Test
    void boundsLabelsByUnicodeCodePoint() {
        final String value = "😀".repeat(200);
        final String bounded = WindowsHistoryManagerValidationProbe.boundedLabel(value);
        assertEquals(160, bounded.codePointCount(0, bounded.length()));
        assertEquals("", WindowsHistoryManagerValidationProbe.boundedLabel(null));
    }

    @Test
    void semanticScalarProjectionRejectsUnknownObjectStringConversion() {
        final Object explosive = new Object() {
            @Override
            public String toString() {
                throw new AssertionError("must not stringify unknown host objects");
            }
        };
        assertEquals("", WindowsHistoryManagerValidationProbe.safeScalar(explosive));
        assertEquals("", WindowsHistoryManagerValidationProbe.boundedLabel(explosive));
        assertEquals("7", WindowsHistoryManagerValidationProbe.safeScalar(7));
    }

    @Test
    void semanticChildDegradationDoesNotMarkGroupTruncated() {
        final WindowsHistoryManagerValidationProbe.NativeDetail child = simpleUnavailable();

        assertFalse(WindowsHistoryManagerValidationProbe.groupTruncated(
            1, 1, 1, List.of(child)
        ));
        assertEquals("history.detail.post-state-unavailable", child.degradationCode());
    }

    @Test
    void observedCountMismatchRemainsTruncated() {
        assertTrue(WindowsHistoryManagerValidationProbe.groupTruncated(
            2, 1, 1, List.of(simpleUnavailable())
        ));
    }

    @Test
    void projectionBudgetRemainsTruncated() {
        assertTrue(WindowsHistoryManagerValidationProbe.groupTruncated(
            2, 2, 1, List.of(simpleUnavailable(), simpleUnavailable())
        ));
    }

    @Test
    void depthOrNodeLimitRemainsTruncated() {
        final WindowsHistoryManagerValidationProbe.NativeDetail limited =
            WindowsHistoryManagerValidationProbe.NativeDetail.degraded(
                "com.live2d.undo.SimpleUndo",
                "LIMIT",
                "history.detail.node-or-depth-limit"
            );

        assertTrue(limited.truncated());
        assertTrue(WindowsHistoryManagerValidationProbe.groupTruncated(
            1, 1, 1, List.of(limited)
        ));
    }

    @Test
    void nestedTraversalLimitPropagates() {
        final WindowsHistoryManagerValidationProbe.NativeDetail limited =
            WindowsHistoryManagerValidationProbe.NativeDetail.degraded(
                "com.live2d.undo.SimpleUndo",
                "LIMIT",
                "history.detail.node-or-depth-limit"
            );
        final WindowsHistoryManagerValidationProbe.NativeDetail nested = group(
            1,
            List.of(limited),
            "history.detail.group-truncated"
        );

        assertTrue(nested.truncated());
        assertTrue(WindowsHistoryManagerValidationProbe.groupTruncated(
            1, 1, 1, List.of(nested)
        ));
    }

    @Test
    void decoderFailureIsNotTruncation() {
        final WindowsHistoryManagerValidationProbe.NativeDetail failed =
            WindowsHistoryManagerValidationProbe.NativeDetail.degraded(
                "com.live2d.undo.SimpleUndo",
                "FAILED",
                "history.detail.decoder-failed"
            );

        assertFalse(WindowsHistoryManagerValidationProbe.groupTruncated(
            1, 1, 1, List.of(failed)
        ));
        assertEquals("history.detail.decoder-failed", failed.degradationCode());
    }

    @Test
    void anObjectMoveIsASummaryOfMagnitudesNotOfTheMesh() throws Exception {
        final WindowsHistoryManagerValidationProbe.GeometryDelta delta =
            WindowsHistoryManagerValidationProbe.geometryDelta(
                form(0.0F, 0.0F, 1.0F, 1.0F, 2.0F, 0.5F),
                form(3.0F, 4.0F, 4.0F, 5.0F, 5.0F, 4.5F)
            );

        assertEquals("POSITIONS", delta.family());
        assertEquals(3, delta.pointCount());
        assertTrue(delta.changed());
        assertEquals("3.000000", delta.translationX());
        assertEquals("4.000000", delta.translationY());
        assertEquals("0.000000", delta.maxDeviation());
        assertEquals("", delta.degradationCode());
    }

    @Test
    void aMeshEditIsDistinguishableFromAMoveByItsDeviation() throws Exception {
        final WindowsHistoryManagerValidationProbe.GeometryDelta delta =
            WindowsHistoryManagerValidationProbe.geometryDelta(
                form(0.0F, 0.0F, 1.0F, 1.0F),
                form(0.0F, 0.0F, 5.0F, 1.0F)
            );

        assertEquals("POSITIONS", delta.family());
        assertTrue(delta.changed());
        assertFalse("0.000000".equals(delta.maxDeviation()),
            "a deformed shape must not look like a pure translation");
    }

    @Test
    void anUnchangedFormIsReportedAsUnchanged() throws Exception {
        final WindowsHistoryManagerValidationProbe.GeometryDelta delta =
            WindowsHistoryManagerValidationProbe.geometryDelta(
                form(0.0F, 0.0F, 1.0F, 1.0F),
                form(0.0F, 0.0F, 1.0F, 1.0F)
            );

        assertEquals("POSITIONS", delta.family());
        assertFalse(delta.changed());
    }

    @Test
    void aFormWhosePositionsCannotBeComparedDegrades() throws Exception {
        assertEquals(
            "history.geometry.shape-changed",
            WindowsHistoryManagerValidationProbe.geometryDelta(
                form(0.0F, 0.0F),
                form(0.0F, 0.0F, 1.0F, 1.0F)
            ).degradationCode()
        );
        assertEquals(
            "history.geometry.value-unsupported",
            WindowsHistoryManagerValidationProbe.geometryDelta(
                form(0.0F, 0.0F),
                form(Float.NaN, 0.0F)
            ).degradationCode()
        );
    }

    @Test
    void aFormOverThePointLimitDegradesRatherThanSummarisingInPart() throws Exception {
        final float[] huge = new float[(WindowsHistoryManagerValidationProbe.MAX_GEOMETRY_POINTS + 1) * 2];

        assertEquals(
            "history.geometry.point-limit",
            WindowsHistoryManagerValidationProbe.geometryDelta(
                new Form(huge.clone()),
                new Form(huge.clone())
            ).degradationCode()
        );
    }

    @Test
    void anEntryThatCarriesNoFormPositionsIsReportedAsNone() throws Exception {
        // A SimpleUndo over something that is not an ArtMesh form proves no geometry at all.
        assertEquals(
            "NONE",
            WindowsHistoryManagerValidationProbe.geometryDelta(new Object(), new Object()).family()
        );
        assertEquals(
            "NONE",
            WindowsHistoryManagerValidationProbe.geometryDelta(null, form(0.0F, 0.0F)).family()
        );
    }

    @Test
    void geometryAppearsInTheDetailJson() {
        final WindowsHistoryManagerValidationProbe.NativeDetail detail =
            new WindowsHistoryManagerValidationProbe.NativeDetail(
                "SIMPLE", "com.live2d.undo.SimpleUndo", "target", "", "", "", "", -1,
                0, List.of(), List.of(), true, true, "",
                WindowsHistoryManagerValidationProbe.GeometryDelta.summary(4, true, 1.5, -2.5, 0.25)
            );

        final String json = detail.json();

        assertTrue(json.contains("\"geometry\":{\"family\":\"POSITIONS\""), json);
        assertTrue(json.contains("\"translationX\":\"1.500000\""), json);
        assertTrue(json.contains("\"translationY\":\"-2.500000\""), json);
        assertTrue(json.contains("\"maxDeviation\":\"0.250000\""), json);
        assertFalse(
            json.contains("0.0,1.0") || json.contains("positions\":["),
            "the artifact must never carry vertex coordinates"
        );
    }

    private static Object form(final float... positions) {
        return new Form(positions);
    }

    /** Test double for the only thing the summary reads: a public {@code getPositions}. */
    public static final class Form {
        private final float[] positions;

        Form(final float[] positions) {
            this.positions = positions;
        }

        public float[] getPositions() {
            return positions;
        }
    }

    private static WindowsHistoryManagerValidationProbe.NativeDetail simpleUnavailable() {
        return new WindowsHistoryManagerValidationProbe.NativeDetail(
            "SIMPLE", "com.live2d.undo.SimpleUndo", "target", "", "", "", "", -1,
            0, List.of(), List.of(), true, false, "history.detail.post-state-unavailable"
        );
    }

    private static WindowsHistoryManagerValidationProbe.NativeDetail group(
        final int observedChildCount,
        final List<WindowsHistoryManagerValidationProbe.NativeDetail> children,
        final String degradationCode
    ) {
        return new WindowsHistoryManagerValidationProbe.NativeDetail(
            "GROUP", "com.live2d.undo.GroupUndo", "", "", "", "", "", -1,
            observedChildCount,
            children.stream().map(WindowsHistoryManagerValidationProbe.NativeDetail::entryClass).toList(),
            children,
            false,
            false,
            degradationCode
        );
    }
}
