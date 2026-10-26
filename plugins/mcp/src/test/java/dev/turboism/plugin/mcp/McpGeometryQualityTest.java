package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.json.Json;
import java.util.*;
import org.junit.jupiter.api.Test;

class McpGeometryQualityTest {
    static final List<Point2> UNIT = points(0, 0, 1, 0, 0, 1);
    static final List<Integer> TRI = List.of(0, 1, 2);
    static final McpGeometryQuality.Thresholds LIMITS = new McpGeometryQuality.Thresholds(1e-8, 2, .5, 10);

    @Test
    void comparesStoredReferenceWithoutMixingSourceCoordinates() {
        var source = points(0, 0, -100, 0, 0, 100);
        var target = points(10, 10, 10, 11, 9, 10);
        var snapshot = mesh(source, TRI, UNIT, target);
        var report = analyze(snapshot, false, 200);
        assertEquals("NO_FINDINGS_FOR_IMPLEMENTED_CHECKS", report.verdict());
        assertEquals(0, report.findingCount());
        assertEquals(1, report.checkedGeometries());
        assertEquals(source, snapshot.sourceGeometry().positions());
        assertEquals(target, snapshot.forms().get(1).positions());
        assertThrows(
                UnsupportedOperationException.class, () -> report.checkedForms().clear());
    }

    @Test
    void findsRelativeWindingAndLocatesStoredTriangle() {
        var report = analyze(mesh(UNIT, TRI, UNIT, points(0, 0, -1, 0, 0, 1)), false, 200);
        var finding = report.findings().stream()
                .filter(r -> "WINDING_REVERSED".equals(r.get("code")))
                .findFirst()
                .orElseThrow();
        assertEquals("target", finding.get("formGuid"));
        assertEquals(0, finding.get("triangleIndex"));
        assertEquals(TRI, finding.get("indices"));
        assertEquals("FINDINGS", report.verdict());
        assertThrows(UnsupportedOperationException.class, () -> finding.put("code", "changed"));
    }

    @Test
    void reportsStretchCompressionAndNeedleTriangles() {
        var stretched = analyze(mesh(UNIT, TRI, UNIT, points(0, 0, 5, 0, 0, 1)), false, 200);
        assertTrue(codes(stretched).contains("EDGE_STRETCHED"));
        var compressed = analyze(mesh(UNIT, TRI, UNIT, points(0, 0, .1F, 0, 0, .1F)), false, 200);
        assertTrue(codes(compressed).contains("EDGE_COMPRESSED"));
        var needle = analyze(mesh(UNIT, TRI, UNIT, points(0, 0, 1, 0, 0, .0001F)), false, 200);
        assertTrue(codes(needle).contains("TRIANGLE_ASPECT_HIGH"));
    }

    @Test
    void collapsedTargetAndDegenerateReferenceNeverReportCompleteComparison() {
        var collapsed = points(0, 0, 1, 0, 2, 0);
        var target = analyze(mesh(UNIT, TRI, UNIT, collapsed), false, 200);
        assertTrue(codes(target).contains("DEGENERATE_TRIANGLE"));
        assertEquals("INCOMPLETE", target.verdict());
        var zeroEdge = points(0, 0, 0, 0, 0, 1);
        var reference = analyze(mesh(UNIT, TRI, zeroEdge, UNIT), false, 200);
        assertTrue(codes(reference).containsAll(Set.of("REFERENCE_TRIANGLE_DEGENERATE", "REFERENCE_EDGE_ZERO")));
        assertTrue(reference.skippedComparisons() >= 2);
        assertEquals("INCOMPLETE", reference.verdict());
        assertFalse(Json.stringify(reference.findings()).contains("NaN"));
        assertFalse(Json.stringify(reference.findings()).contains("Infinity"));
    }

    @Test
    void detectsTopologyIndependentOfStoredFormSelection() {
        var p = points(0, 0, 1, 0, 0, 1, 1, 1, 2, 2);
        var report = analyze(mesh(p, List.of(0, 1, 2, 2, 1, 0, 0, 1, 3), p, p), true, 200);
        assertTrue(codes(report).containsAll(Set.of("DUPLICATE_TRIANGLE", "NON_MANIFOLD_EDGE", "UNUSED_VERTEX")));
        var winding = analyze(mesh(p, List.of(0, 1, 2, 0, 1, 3), p, p), false, 200);
        assertTrue(codes(winding).contains("INCONSISTENT_TOPOLOGY_WINDING"));
    }

    @Test
    void truncationCountsAllFindingsAndNeverReturnsClear() {
        var report = analyze(mesh(UNIT, TRI, UNIT, points(0, 0, 0, 0, 0, 0)), true, 1);
        assertTrue(report.findingsTruncated());
        assertEquals(1, report.findings().size());
        assertTrue(report.findingCount() > 1);
        assertEquals("INCOMPLETE", report.verdict());
    }

    @Test
    void sourceOnlyAndEmptyGeometryHaveExplicitScope() {
        var empty = new ArtMeshAuthoringSnapshot(
                new ArtMeshId("mesh"),
                "source",
                new ArtMeshGeometry(List.of(), List.of(), List.of()),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        var report = McpGeometryQuality.analyze(empty, true, null, List.of(), LIMITS, 200);
        assertTrue(codes(report).contains("EMPTY_GEOMETRY"));
        assertEquals("FINDINGS", report.verdict());
        assertEquals(List.of(), report.checkedForms());
        assertThrows(
                IllegalArgumentException.class,
                () -> McpGeometryQuality.analyze(empty, false, null, List.of(), LIMITS, 200));
    }

    @Test
    void permitsOrdinaryDefaultAndSharedFormsForReadOnlyDiagnostics() {
        var b = mesh(UNIT, TRI, UNIT, UNIT);
        var shared = new ArtMeshAuthoringSnapshot(
                b.artMeshId(),
                b.sourceGuid(),
                b.sourceGeometry(),
                List.of(new ArtMeshAuthoringSnapshot.Axis(
                        "axis", new ParameterId("display"), 0, 0, 2, List.of(0F, 1F, 2F))),
                List.of(
                        b.cells().get(0),
                        b.cells().get(1),
                        new ArtMeshAuthoringSnapshot.Cell(
                                "target", List.of(new ArtMeshAuthoringSnapshot.Coordinate("axis", 2)))),
                b.forms(),
                List.of());
        assertEquals(
                "NO_FINDINGS_FOR_IMPLEMENTED_CHECKS",
                analyze(shared, false, 200).verdict());
        assertEquals(
                "NO_FINDINGS_FOR_IMPLEMENTED_CHECKS",
                McpGeometryQuality.analyze(shared, false, "target", List.of("reference"), LIMITS, 200)
                        .verdict());
    }

    @Test
    void rejectsMorphUnknownFormsAndInvalidScope() {
        var b = mesh(UNIT, TRI, UNIT, UNIT);
        var morph = new ArtMeshAuthoringSnapshot(
                b.artMeshId(),
                b.sourceGuid(),
                b.sourceGeometry(),
                b.axes(),
                b.cells(),
                b.forms(),
                List.of(new ArtMeshAuthoringSnapshot.MorphKey("morph", "target", Optional.empty())));
        assertThrows(UnsupportedOperationException.class, () -> analyze(morph, false, 200));
        assertThrows(
                NoSuchElementException.class,
                () -> McpGeometryQuality.analyze(b, false, "missing", List.of("target"), LIMITS, 200));
        assertThrows(
                IllegalArgumentException.class,
                () -> McpGeometryQuality.analyze(b, false, null, List.of("target"), LIMITS, 200));
        assertThrows(
                IllegalArgumentException.class,
                () -> McpGeometryQuality.analyze(b, false, "reference", List.of("target", "target"), LIMITS, 200));
    }

    @Test
    void rejectsExcessiveWorkBeforeWalkingTopology() {
        var indices = new ArrayList<Integer>();
        for (int i = 0; i < 100000; i++) indices.addAll(TRI);
        var b = mesh(UNIT, indices, UNIT, UNIT);
        assertThrows(McpGeometryQuality.BudgetExceeded.class, () -> analyze(b, false, 200));
    }

    @Test
    void validatesFiniteThresholdsAndRelativeArea() {
        assertThrows(IllegalArgumentException.class, () -> new McpGeometryQuality.Thresholds(Double.NaN, 2, .5, 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> new McpGeometryQuality.Thresholds(0, Double.POSITIVE_INFINITY, .5, 10));
        assertThrows(IllegalArgumentException.class, () -> new McpGeometryQuality.Thresholds(0, 2, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new McpGeometryQuality.Thresholds(0, 2, .5, .9));
        var b = mesh(UNIT, TRI, UNIT, points(0, 0, 1, 0, 0, .01F));
        var report = McpGeometryQuality.analyze(
                b, false, "reference", List.of("target"), new McpGeometryQuality.Thresholds(.1, 2, .5, 100), 200);
        assertTrue(codes(report).contains("DEGENERATE_TRIANGLE"));
    }

    @Test
    void finiteFloatExtremesDoNotOverflowDiagnosticNumbers() {
        for (float scale : new float[] {Float.MAX_VALUE, Float.MIN_NORMAL}) {
            var p = points(-scale, 0, scale, 0, 0, scale);
            var report = analyze(mesh(p, TRI, p, p), true, 200);
            assertEquals("NO_FINDINGS_FOR_IMPLEMENTED_CHECKS", report.verdict());
        }
    }

    @Test
    void overlapFindingsLocateBothStoredTrianglesAndKeepSourceSeparate() {
        var indices = List.of(0, 1, 2, 3, 4, 5);
        var source = points(0, 0, 2, 0, 0, 2, 3, 3, 4, 3, 3, 4);
        var target = points(0, 0, 2, 0, 0, 2, .2F, .2F, .5F, .2F, .2F, .5F);
        var report = analyze(mesh(source, indices, source, target), true, 200);
        var finding = report.findings().stream()
                .filter(row -> "TRIANGLE_INTERIOR_OVERLAP".equals(row.get("code")))
                .findFirst()
                .orElseThrow();
        assertEquals("stored", finding.get("scope"));
        assertEquals("target", finding.get("formGuid"));
        assertEquals(0, finding.get("triangleIndex"));
        assertEquals(1, finding.get("relatedTriangleIndex"));
        assertEquals(List.of(0, 1, 2), finding.get("indices"));
        assertTrue(report.intersectionCheckComplete());
        assertEquals(1, report.intersectionPairsTested());
        assertTrue(report.estimatedWork() <= McpGeometryQuality.MAX_WORK);
    }

    @Test
    void pairBudgetExhaustionProducesIncompleteRatherThanFalseClear() {
        var indices = new ArrayList<Integer>();
        for (int i = 0; i < 800; i++) indices.addAll(TRI);
        var report = analyze(mesh(UNIT, indices, UNIT, UNIT), false, 200);
        assertFalse(report.intersectionCheckComplete());
        assertEquals("INCOMPLETE", report.verdict());
        assertTrue(report.intersectionPairsTested() > 0);
        assertTrue(report.intersectionPairsTested() < 800L * 799 / 2);
        assertTrue(report.estimatedWork() <= McpGeometryQuality.MAX_WORK);
    }

    static McpGeometryQuality.Report analyze(ArtMeshAuthoringSnapshot b, boolean source, int cap) {
        return McpGeometryQuality.analyze(b, source, "reference", List.of("target"), LIMITS, cap);
    }

    static Set<String> codes(McpGeometryQuality.Report r) {
        var result = new HashSet<String>();
        r.findings().forEach(row -> result.add((String) row.get("code")));
        return result;
    }

    static List<Point2> points(float... values) {
        var result = new ArrayList<Point2>();
        for (int i = 0; i < values.length; i += 2) result.add(new Point2(values[i], values[i + 1]));
        return List.copyOf(result);
    }

    static ArtMeshAuthoringSnapshot mesh(
            List<Point2> source, List<Integer> indices, List<Point2> reference, List<Point2> target) {
        return new ArtMeshAuthoringSnapshot(
                new ArtMeshId("mesh"),
                "source",
                new ArtMeshGeometry(source, source, indices),
                List.of(new ArtMeshAuthoringSnapshot.Axis(
                        "axis", new ParameterId("display"), 0, 0, 1, List.of(0F, 1F))),
                List.of(
                        new ArtMeshAuthoringSnapshot.Cell(
                                "reference", List.of(new ArtMeshAuthoringSnapshot.Coordinate("axis", 0))),
                        new ArtMeshAuthoringSnapshot.Cell(
                                "target", List.of(new ArtMeshAuthoringSnapshot.Coordinate("axis", 1)))),
                List.of(
                        new ArtMeshAuthoringSnapshot.Form("reference", reference, 500),
                        new ArtMeshAuthoringSnapshot.Form("target", target, 500)),
                List.of());
    }
}
