package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.*;
import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class McpGeometryValidationDomainTest {
    @Test
    void threeAxisMeshPlanCanBeDiagnosedWithoutChangingStoredSnapshot() {
        var b = McpLandmarkThreeAxisPlanTest.mesh(McpLandmarkThreeAxisPlanTest.grid(3, 3));
        var snapshot = new ArtMeshAuthoringSnapshot(
                new ArtMeshId("mesh"),
                b.sourceGuid(),
                b.sourceGeometry(),
                b.axes(),
                b.cells(),
                b.forms(),
                b.morphKeys());
        var edits = McpMeshRigPlan.edits(
                snapshot, McpLandmarkThreeAxisPlanTest.axes(3), McpLandmarkThreeAxisPlanTest.allCorners());
        var f = new Fixture();
        f.replaceLive(snapshot);
        f.args.put("referenceFormGuid", "default");
        f.args.put(
                "formGuids", edits.stream().map(e -> (String) e.get("formGuid")).toList());
        f.args.put("proposedEdits", edits);
        var result = f.call();
        assertEquals(true, result.get("ok"), result.toString());
        assertEquals(26, result.get("proposedEditCount"));
        assertEquals(true, result.get("analysisComplete"));
        assertEquals(snapshot, f.live);
        assertEquals(2, f.reads);
    }

    @Test
    void successfulReadChecksFullNativeStateTwiceAndReportsLimitedScope() {
        var f = new Fixture();
        var result = f.call();
        assertEquals(true, result.get("ok"), result.toString());
        assertEquals(2, f.reads);
        assertEquals("NO_FINDINGS_FOR_IMPLEMENTED_CHECKS", result.get("verdict"));
        assertTrue(((List<?>) result.get("notImplementedChecks")).contains("VISUAL_ART_QUALITY"));
        assertEquals(true, result.get("comparisonComplete"));
        assertEquals(
                McpOperationEffect.READ,
                f.tools.registration(McpGeometryValidationDomain.VALIDATE).effect());
        assertFalse(f.tools.registration(McpGeometryValidationDomain.VALIDATE).transactionEligible());
    }

    @Test
    void nativeChangeAtSameHistoryRevisionIsStale() {
        var f = new Fixture();
        f.live = McpGeometryQualityTest.mesh(
                McpGeometryQualityTest.UNIT,
                McpGeometryQualityTest.TRI,
                McpGeometryQualityTest.UNIT,
                McpGeometryQualityTest.points(0, 0, 2, 0, 0, 1));
        error(f.call(), "STALE_STATE");
        assertEquals(1, f.reads);
    }

    @Test
    void postAnalysisReadRejectsUnrecordedNativeChange() {
        var f = new Fixture();
        f.changeOnSecondRead = true;
        error(f.call(), "STALE_STATE");
        assertEquals(2, f.reads);
    }

    @Test
    void documentHistoryParentAndSourceIdentityAreChecked() {
        var f = new Fixture();
        f.binding.put("documentId", "other");
        error(f.call(), "STALE_STATE");
        f = new Fixture();
        f.binding.put("historyRevision", 2L);
        error(f.call(), "STALE_STATE");
        f = new Fixture();
        f.parent = Optional.of(new PartId("new-parent"));
        error(f.call(), "STALE_STATE");
        f = new Fixture();
        var b = f.live;
        f.live = new ArtMeshAuthoringSnapshot(
                b.artMeshId(), "recreated-source", b.sourceGeometry(), b.axes(), b.cells(), b.forms(), b.morphKeys());
        error(f.call(), "STALE_STATE");
    }

    @Test
    void sessionsExpiryAndUnknownTokensDoNotProduceVerdicts() {
        var f = new Fixture();
        error(McpRequestRegistry.inSession("other", f::call), "NOT_FOUND");
        f.now = McpAuthoringSnapshotStore.TTL_NANOS;
        error(f.call(), "NOT_FOUND");
    }

    @Test
    void readFailuresFailClosedWithoutNativeExceptionDetails() {
        var f = new Fixture();
        f.failure = new SecurityException("secret native path");
        error(f.call(), "PERMISSION_DENIED");
        f = new Fixture();
        f.failure = new UnsupportedOperationException("secret native path");
        error(f.call(), "UNAVAILABLE");
        f = new Fixture();
        f.failure = new IllegalStateException("secret native path");
        error(f.call(), "READ_FAILED");
    }

    @Test
    void sourceOnlyClosedSchemaAndMissingFormsAreValidated() {
        var f = new Fixture();
        f.args.put("includeSource", true);
        f.args.put("formGuids", List.of());
        f.args.put("referenceFormGuid", null);
        assertEquals(true, f.call().get("ok"));
        f.args.put("unexpected", true);
        assertThrows(IllegalArgumentException.class, f::call);
        f = new Fixture();
        f.args.put("referenceFormGuid", "missing");
        error(f.call(), "NOT_FOUND");
        f = new Fixture();
        f.args.put("referenceFormGuid", null);
        error(f.call(), "INVALID_ARGUMENT");
    }

    private static void error(Map<String, Object> result, String code) {
        assertEquals(false, result.get("ok"), result.toString());
        assertEquals(code, ((Map<?, ?>) result.get("error")).get("code"));
        assertFalse(result.containsKey("verdict"));
        assertFalse(result.toString().contains("secret native path"));
    }

    @Test
    void overlapPairAndCompletionStatisticsSurviveClosedOutputSchema() {
        var f = new Fixture();
        var points = McpGeometryQualityTest.points(0, 0, 4, 0, 0, 4, 1, 1, 2, 1, 1, 2);
        f.replaceLive(McpGeometryQualityTest.mesh(points, List.of(0, 1, 2, 3, 4, 5), points, points));
        var result = f.call();
        assertEquals(true, result.get("ok"), result.toString());
        assertEquals(true, result.get("analysisComplete"));
        assertEquals(true, result.get("intersectionCheckComplete"));
        var findings = (List<?>) result.get("findings");
        var pair = (Map<?, ?>) findings.stream()
                .filter(row -> "TRIANGLE_INTERIOR_OVERLAP".equals(((Map<?, ?>) row).get("code")))
                .findFirst()
                .orElseThrow();
        assertEquals(0, pair.get("triangleIndex"));
        assertEquals(1, pair.get("relatedTriangleIndex"));
        var stats = (Map<?, ?>) result.get("statistics");
        assertEquals(1L, stats.get("intersectionPairsTested"));
        assertTrue(((Number) stats.get("estimatedWork")).longValue() <= McpGeometryQuality.MAX_WORK);
        assertTrue(((List<?>) result.get("notImplementedChecks")).contains("TRIANGLE_BOUNDARY_CONTACTS"));
    }

    @Test
    void budgetLimitedOverlapIsIncompleteEvenThoughTheReadSucceeded() {
        var f = new Fixture();
        var indices = new ArrayList<Integer>();
        for (int i = 0; i < 800; i++) indices.addAll(McpGeometryQualityTest.TRI);
        f.replaceLive(McpGeometryQualityTest.mesh(
                McpGeometryQualityTest.UNIT, indices, McpGeometryQualityTest.UNIT, McpGeometryQualityTest.UNIT));
        var result = f.call();
        assertEquals(true, result.get("ok"), result.toString());
        assertEquals("INCOMPLETE", result.get("verdict"));
        assertEquals(false, result.get("analysisComplete"));
        assertEquals(false, result.get("comparisonComplete"));
        assertEquals(false, result.get("intersectionCheckComplete"));
        var stats = (Map<?, ?>) result.get("statistics");
        assertTrue(((Number) stats.get("intersectionPairsTested")).longValue() > 0);
        assertTrue(((Number) stats.get("estimatedWork")).longValue() <= McpGeometryQuality.MAX_WORK);
    }

    @Test
    void proposedPositionsAreCheckedWithoutMutatingLiveGeometry() {
        var f = new Fixture();
        var before = f.live;
        f.args.put("proposedEdits", List.of(proposed("target", 1, Collections.nCopies(3, new Point2(0, 0)))));
        var r = f.call();
        assertEquals(true, r.get("ok"), r.toString());
        assertEquals(1, r.get("proposedEditCount"));
        assertTrue(r.toString().contains("DEGENERATE_TRIANGLE"));
        assertEquals(before, f.live);
        assertEquals(2, f.reads);
    }

    @Test
    void proposalsRejectReferenceUnselectedWrongTupleAndNonPositionFields() {
        var f = new Fixture();
        f.args.put("proposedEdits", List.of(proposed("reference", 0, McpGeometryQualityTest.UNIT)));
        error(f.call(), "INVALID_ARGUMENT");
        f = new Fixture();
        f.args.put("formGuids", List.of("reference"));
        f.args.put("proposedEdits", List.of(proposed("target", 1, McpGeometryQualityTest.UNIT)));
        error(f.call(), "INVALID_ARGUMENT");
        f = new Fixture();
        f.args.put("proposedEdits", List.of(proposed("target", 0, McpGeometryQualityTest.UNIT)));
        error(f.call(), "INVALID_ARGUMENT");
        f = new Fixture();
        var extra = new LinkedHashMap<>(proposed("target", 1, McpGeometryQualityTest.UNIT));
        extra.put("drawOrder", 10);
        f.args.put("proposedEdits", List.of(extra));
        assertThrows(IllegalArgumentException.class, f::call);
    }

    @Test
    void actualMeshLandmarkPlanCanBeDiagnosedBeforeSeparateWrite() {
        var nativeMesh = McpMeshRigPlanTest.fromWarp(McpWarpRigPlanTest.snapshot(true));
        var mesh = new ArtMeshAuthoringSnapshot(
                new ArtMeshId("mesh"),
                nativeMesh.sourceGuid(),
                nativeMesh.sourceGeometry(),
                nativeMesh.axes(),
                nativeMesh.cells(),
                nativeMesh.forms(),
                nativeMesh.morphKeys());
        var edits = McpMeshRigPlan.edits(mesh, McpWarpRigPlanTest.axes(true));
        var f = new Fixture();
        f.replaceLive(mesh);
        f.args.put("referenceFormGuid", "f1.0:0.0");
        f.args.put("formGuids", edits.stream().map(e -> e.get("formGuid")).toList());
        f.args.put("proposedEdits", edits);
        var r = f.call();
        assertEquals(true, r.get("ok"), r.toString());
        assertEquals(8, r.get("proposedEditCount"));
        assertEquals(mesh, f.live);
        assertEquals(2, f.reads);
    }

    static Map<String, Object> proposed(String guid, int key, List<Point2> points) {
        return Map.of(
                "formGuid",
                guid,
                "coordinates",
                List.of(Map.of("parameterGuid", "axis", "value", key)),
                "positions",
                points.stream().map(p -> Map.of("x", p.x(), "y", p.y())).toList());
    }

    private static final class Fixture {
        long now;
        int reads;
        boolean changeOnSecondRead;
        RuntimeException failure;
        Optional<PartId> parent = Optional.empty();
        ArtMeshAuthoringSnapshot live = McpGeometryQualityTest.mesh(
                McpGeometryQualityTest.UNIT,
                McpGeometryQualityTest.TRI,
                McpGeometryQualityTest.UNIT,
                McpGeometryQualityTest.UNIT);
        final Map<String, Object> binding = new LinkedHashMap<>(Map.of(
                "documentId",
                "doc",
                "modelId",
                "model",
                "historyDocumentBinding",
                "hd",
                "historyManagerBinding",
                "hm",
                "historyGeneration",
                1L,
                "historyRevision",
                1L));
        final Drawable drawable = proxy(Drawable.class, (name, a) -> switch (name) {
            case "authoringSnapshot" -> {
                reads++;
                if (failure != null) throw failure;
                if (changeOnSecondRead && reads == 2)
                    yield McpGeometryQualityTest.mesh(
                            McpGeometryQualityTest.UNIT,
                            McpGeometryQualityTest.TRI,
                            McpGeometryQualityTest.UNIT,
                            McpGeometryQualityTest.points(0, 0, 3, 0, 0, 1));
                yield live;
            }
            case "parentPartId" -> parent;
            case "parentDeformerId" -> Optional.empty();
            default -> throw new AssertionError("Read-only tool unexpectedly called " + name);
        });
        final CubismModel model = proxy(CubismModel.class, (name, a) -> {
            if (name.equals("drawables"))
                return proxy(Drawables.class, (n, v) -> {
                    if (n.equals("find")) return drawable;
                    throw new AssertionError(n);
                });
            throw new AssertionError(name);
        });
        final McpAuthoringSnapshotStore store = new McpAuthoringSnapshotStore(() -> now);
        final McpExecutionBridge execution = new McpExecutionBridge(new UiScheduler() {
            public Registration runOnUiThread(Runnable work) {
                work.run();
                return () -> {};
            }

            public Registration runOnUiThreadLater(Runnable work, Duration delay) {
                throw new AssertionError();
            }
        });
        final McpToolCatalog tools = new McpGeometryValidationDomain(
                        store, execution, () -> new McpStoredAuthoringDomain.Context(model, Map.copyOf(binding)))
                .tools();
        final Map<String, Object> args = new LinkedHashMap<>();

        void replaceLive(ArtMeshAuthoringSnapshot snapshot) {
            live = snapshot;
            var row = new LinkedHashMap<String, Object>();
            row.put("kind", "parent_space");
            row.put("parentPartId", null);
            row.put("parentDeformerId", null);
            var issued = store.put(
                    McpRequestRegistry.currentSessionId(),
                    binding,
                    "art_mesh",
                    "mesh",
                    "source",
                    live,
                    List.of(row),
                    1000);
            args.put("snapshotId", issued.snapshotId());
        }

        Fixture() {
            var row = new LinkedHashMap<String, Object>();
            row.put("kind", "parent_space");
            row.put("parentPartId", null);
            row.put("parentDeformerId", null);
            var entry = store.put(
                    McpRequestRegistry.currentSessionId(),
                    binding,
                    "art_mesh",
                    "mesh",
                    "source",
                    live,
                    List.of(row),
                    1000);
            args.putAll(Map.of(
                    "documentId",
                    "doc",
                    "modelId",
                    "model",
                    "objectId",
                    "mesh",
                    "snapshotId",
                    entry.snapshotId(),
                    "includeSource",
                    false,
                    "referenceFormGuid",
                    "reference",
                    "formGuids",
                    List.of("target"),
                    "thresholds",
                    Map.of(
                            "minimumAreaRatio",
                            1e-8,
                            "maximumEdgeStretch",
                            2,
                            "minimumEdgeCompression",
                            .5,
                            "maximumTriangleAspectRatio",
                            10),
                    "maxFindings",
                    200));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> call() {
            return (Map<String, Object>)
                    tools.call(McpGeometryValidationDomain.VALIDATE, args).get("structuredContent");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.util.function.BiFunction<String, Object[], Object> handler) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> handler.apply(m.getName(), a));
    }
}
