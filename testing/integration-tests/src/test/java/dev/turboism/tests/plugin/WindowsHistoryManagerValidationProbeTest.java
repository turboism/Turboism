package dev.turboism.tests.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEditContext;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryGroup;
import dev.turboism.sdk.cubism.history.HistoryOrigin;
import dev.turboism.sdk.cubism.history.HistoryRelationChange;
import dev.turboism.sdk.cubism.history.HistoryTarget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsHistoryManagerValidationProbeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

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

    @Test
    void anExhaustedGeometryBudgetReportsOmittedRatherThanNone() throws Exception {
        final int[] budget = {1};

        assertEquals(
            "POSITIONS",
            WindowsHistoryManagerValidationProbe.budgetedGeometry(
                budget, form(0.0F, 0.0F), form(1.0F, 0.0F)
            ).family()
        );
        assertEquals(0, budget[0], "one summary must spend exactly one unit of the budget");

        final WindowsHistoryManagerValidationProbe.GeometryDelta exhausted =
            WindowsHistoryManagerValidationProbe.budgetedGeometry(
                budget, form(0.0F, 0.0F), form(1.0F, 0.0F)
            );

        assertEquals("OMITTED", exhausted.family());
        assertFalse(exhausted.changed(), "an omitted summary claims no change");
        assertFalse(
            "NONE".equals(exhausted.family()),
            "a spent budget must not look like a detail that carries no form at all"
        );
        assertEquals(
            "OMITTED",
            WindowsHistoryManagerValidationProbe.budgetedGeometry(
                null, form(0.0F, 0.0F), form(1.0F, 0.0F)
            ).family()
        );
    }

    @Test
    void anOmittedSummaryIsSerialisedAsOmitted() {
        final String json = WindowsHistoryManagerValidationProbe.GeometryDelta.omitted().json();

        assertTrue(json.contains("\"family\":\"OMITTED\""), json);
        assertFalse(json.contains("positions"), json);
    }

    @Test
    void sdkDetailJsonSerializesRelationsAndRetainsExistingChangeFields() throws Exception {
        final HistoryTarget child = new HistoryTarget(
            "ART_MESH",
            Optional.of("mesh\"\\id"),
            Optional.of("A \"quoted\" \\ mesh")
        );
        final HistoryTarget oldPart = new HistoryTarget(
            "PART",
            Optional.of("part-old"),
            Optional.of("Old \"Part\" \\ container")
        );
        final HistoryTarget oldDeformer = new HistoryTarget(
            "WARP_DEFORMER",
            Optional.of("warp-old"),
            Optional.of("Old Warp")
        );
        final HistoryRelationChange partMembership = new HistoryRelationChange(
            HistoryRelationChange.Kind.PART_MEMBERSHIP,
            targetEndpoint(oldPart),
            new HistoryRelationChange.Endpoint(HistoryRelationChange.State.ROOT, Optional.empty())
        );
        final HistoryRelationChange deformerParent = new HistoryRelationChange(
            HistoryRelationChange.Kind.DEFORMER_PARENT,
            targetEndpoint(oldDeformer),
            new HistoryRelationChange.Endpoint(HistoryRelationChange.State.UNKNOWN, Optional.empty())
        );
        final HistoryChange ordinaryChange = new HistoryChange(
            HistoryChange.Operation.SET,
            Optional.of(0),
            Optional.of("multiplyColor"),
            Optional.of("#ffffff"),
            Optional.of("#66\"\\ccff"),
            new HistoryEditContext(
                HistoryEditContext.Kind.KEYFORM,
                Optional.of("form\"\\id"),
                List.of(new dev.turboism.sdk.cubism.history.HistoryParameterCoordinate(
                    new HistoryTarget(
                        "PARAMETER",
                        Optional.of("Param\"\\X"),
                        Optional.of("Angle \"X\" \\ axis")
                    ),
                    "1\"\\2"
                ))
            )
        );
        final HistoryChange partChange = relationChange(partMembership);
        final HistoryChange deformerChange = relationChange(deformerParent);
        final HistoryEntryDetail nested = new HistoryEntryDetail(
            "Nested relation",
            HistoryAction.DetailLevel.PARTIAL,
            HistoryOrigin.hostUnattributed(),
            List.of(child),
            List.of(partChange),
            Optional.empty(),
            Optional.of("history.detail.relation-partial")
        );
        final HistoryEntryDetail detail = new HistoryEntryDetail(
            "Relation \"summary\" \\ escaped",
            HistoryAction.DetailLevel.PARTIAL,
            HistoryOrigin.hostUnattributed(),
            List.of(child),
            List.of(ordinaryChange, partChange, deformerChange),
            Optional.of(new HistoryGroup(Optional.of("group\"\\id"), 1, List.of(nested), false)),
            Optional.of("history.detail.relation-partial")
        );

        final JsonNode root = JSON.readTree(WindowsHistoryManagerValidationProbe.sdkDetailJson(detail, 0));
        assertEquals("Relation \"summary\" \\ escaped", root.get("summary").asText());
        assertEquals(3, root.get("changes").size());

        final JsonNode ordinary = root.get("changes").get(0);
        assertEquals("SET", ordinary.get("operation").asText());
        assertEquals(0, ordinary.get("targetIndex").asInt());
        assertEquals("multiplyColor", ordinary.get("property").asText());
        assertEquals("#ffffff", ordinary.get("before").asText());
        assertEquals("#66\"\\ccff", ordinary.get("after").asText());
        assertEquals("KEYFORM", ordinary.get("context").get("kind").asText());
        assertEquals("form\"\\id", ordinary.get("context").get("formId").asText());
        assertEquals("PARAMETER", ordinary.get("context").get("coordinates").get(0)
            .get("parameter").get("type").asText());
        assertEquals("Param\"\\X", ordinary.get("context").get("coordinates").get(0)
            .get("parameter").get("id").asText());
        assertEquals("1\"\\2", ordinary.get("context").get("coordinates").get(0).get("value").asText());
        assertTrue(ordinary.get("relation").isNull());

        final JsonNode part = root.get("changes").get(1).get("relation");
        assertEquals("PART_MEMBERSHIP", part.get("kind").asText());
        assertRelationTarget(part.get("before"), oldPart);
        assertEquals("ROOT", part.get("after").get("state").asText());
        assertTrue(part.get("after").get("target").isNull());

        final JsonNode deformer = root.get("changes").get(2).get("relation");
        assertEquals("DEFORMER_PARENT", deformer.get("kind").asText());
        assertRelationTarget(deformer.get("before"), oldDeformer);
        assertEquals("UNKNOWN", deformer.get("after").get("state").asText());
        assertTrue(deformer.get("after").get("target").isNull());

        final JsonNode nestedChange = root.get("group").get("children").get(0).get("changes").get(0);
        assertEquals("PART_MEMBERSHIP", nestedChange.get("relation").get("kind").asText());
        assertEquals("ROOT", nestedChange.get("relation").get("after").get("state").asText());
        assertTrue(nestedChange.get("relation").get("after").get("target").isNull());
    }

    private static HistoryRelationChange.Endpoint targetEndpoint(final HistoryTarget target) {
        return new HistoryRelationChange.Endpoint(HistoryRelationChange.State.TARGET, Optional.of(target));
    }

    private static HistoryChange relationChange(final HistoryRelationChange relation) {
        return new HistoryChange(
            HistoryChange.Operation.SET,
            Optional.of(0),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            new HistoryEditContext(HistoryEditContext.Kind.OBJECT, Optional.empty(), List.of()),
            Optional.of(relation)
        );
    }

    private static void assertRelationTarget(final JsonNode endpoint, final HistoryTarget expected) {
        assertEquals("TARGET", endpoint.get("state").asText());
        assertEquals(expected.type(), endpoint.get("target").get("type").asText());
        assertEquals(expected.id().orElseThrow(), endpoint.get("target").get("id").asText());
        assertEquals(expected.displayName().orElseThrow(), endpoint.get("target").get("displayName").asText());
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
