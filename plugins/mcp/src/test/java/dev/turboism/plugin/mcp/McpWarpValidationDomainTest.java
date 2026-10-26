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

class McpWarpValidationDomainTest {
    static WarpAuthoringSnapshot snapshot(int identity) {
        var b = McpWarpQualityTest.snapshot(McpWarpQualityTest.UNIT, McpWarpQualityTest.UNIT);
        return new WarpAuthoringSnapshot(
                b.deformerId(),
                identity == 7 ? "source" : "recreated-source",
                b.rows(),
                b.columns(),
                b.quadTransform(),
                b.axes(),
                b.cells(),
                b.forms());
    }

    static void error(Map<String, Object> result, String code) {
        assertEquals(false, result.get("ok"), result.toString());
        assertEquals(code, ((Map<?, ?>) result.get("error")).get("code"));
        assertFalse(result.containsKey("verdict"));
        assertFalse(result.toString().contains("secret path"));
    }

    @Test
    void explicitHeadVolumePreviewIsDiagnosedWithoutChangingSnapshot() {
        var snapshot = McpLandmarkThreeAxisPlanTest.grid(3, 3);
        var edits = McpWarpHeadVolumePlan.edits(
                snapshot, McpWarpHeadVolumePlanTest.axes(15, 10, 5), McpWarpHeadVolumePlanTest.PROFILE);
        var f = new Fixture(snapshot);
        f.args.put("referenceFormGuid", "default");
        f.args.put(
                "formGuids", edits.stream().map(e -> (String) e.get("formGuid")).toList());
        f.args.put("proposedEdits", edits);
        var r = f.call();
        assertEquals(true, r.get("ok"), r.toString());
        assertEquals(26, r.get("proposedEditCount"));
        assertEquals(true, r.get("analysisComplete"));
        assertEquals(snapshot, f.live);
        assertEquals(2, f.reads);
    }

    @Test
    void threeAxisPlanWithPairAndTripleResidualsCanBeDiagnosedWithoutWrite() {
        var snapshot = McpLandmarkThreeAxisPlanTest.grid(3, 3);
        var edits = McpWarpRigPlan.edits(
                snapshot, McpLandmarkThreeAxisPlanTest.axes(3), McpLandmarkThreeAxisPlanTest.allCorners());
        var f = new Fixture(snapshot);
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
    void checksStateTwiceAndReportsScopeWithoutAnyWrites() {
        var f = new Fixture();
        var r = f.call();
        assertEquals(true, r.get("ok"), r.toString());
        assertEquals(2, f.reads);
        assertEquals("NO_FINDINGS_FOR_IMPLEMENTED_CHECKS", r.get("verdict"));
        assertEquals(true, r.get("comparisonComplete"));
        assertEquals(0, r.get("proposedEditCount"));
        assertTrue(((List<?>) r.get("notImplementedChecks")).contains("NATIVE_WARP_INTERPOLATION"));
        assertEquals(
                McpOperationEffect.READ,
                f.tools.registration(McpWarpValidationDomain.VALIDATE).effect());
        assertFalse(f.tools.registration(McpWarpValidationDomain.VALIDATE).transactionEligible());
    }

    @Test
    void proposalsAreDiagnosedWithoutChangingLiveSnapshot() {
        var f = new Fixture();
        f.args.put(
                "proposedEdits",
                List.of(edit(
                        "target",
                        1,
                        List.of(new Point2(0, 0), new Point2(-1, 0), new Point2(0, 1), new Point2(-1, 1)))));
        var r = f.call();
        assertEquals(true, r.get("ok"), r.toString());
        assertEquals(1, r.get("proposedEditCount"));
        assertEquals("FINDINGS", r.get("verdict"));
        assertTrue(r.toString().contains("CELL_ORIENTATION_REVERSED"));
        assertEquals(snapshot(7), f.live);
        assertEquals(2, f.reads);
    }

    @Test
    void referenceAndUnselectedEditsAreRejected() {
        var f = new Fixture();
        f.args.put("proposedEdits", List.of(edit("ref", 0, McpWarpQualityTest.UNIT)));
        error(f.call(), "INVALID_ARGUMENT");
        f = new Fixture();
        f.args.put("formGuids", List.of("ref"));
        f.args.put("proposedEdits", List.of(edit("target", 1, McpWarpQualityTest.UNIT)));
        error(f.call(), "INVALID_ARGUMENT");
        f = new Fixture();
        f.args.put("proposedEdits", List.of(edit("target", 0, McpWarpQualityTest.UNIT)));
        error(f.call(), "INVALID_ARGUMENT");
        f = new Fixture();
        f.args.put("proposedEdits", List.of());
        assertThrows(IllegalArgumentException.class, f::call);
    }

    static Map<String, Object> edit(String guid, int value, List<Point2> p) {
        return Map.of(
                "formGuid",
                guid,
                "coordinates",
                List.of(Map.of("parameterGuid", "axis", "value", value)),
                "controlPoints",
                p.stream().map(v -> Map.of("x", v.x(), "y", v.y())).toList());
    }

    @Test
    void staleNativeParentHistoryAndSecondReadFailClosed() {
        var f = new Fixture();
        f.live = snapshot(8);
        error(f.call(), "STALE_STATE");
        f = new Fixture();
        f.parent = Optional.of(new DeformerId("other"));
        error(f.call(), "STALE_STATE");
        f = new Fixture();
        f.binding.put("historyRevision", 2L);
        error(f.call(), "STALE_STATE");
        f = new Fixture();
        f.changeOnSecondRead = true;
        error(f.call(), "STALE_STATE");
        assertEquals(2, f.reads);
        f = new Fixture();
        f.args.put("modelId", "other");
        error(f.call(), "STALE_STATE");
    }

    @Test
    void sessionExpiryAndReadFailuresNeverProduceAcceptance() {
        var f = new Fixture();
        error(McpRequestRegistry.inSession("other", f::call), "NOT_FOUND");
        f.now = McpAuthoringSnapshotStore.TTL_NANOS;
        error(f.call(), "NOT_FOUND");
        for (var failure : List.of(
                new SecurityException("secret path"),
                new UnsupportedOperationException("secret path"),
                new IllegalStateException("secret path"))) {
            f = new Fixture();
            f.failure = failure;
            error(
                    f.call(),
                    failure instanceof SecurityException
                            ? "PERMISSION_DENIED"
                            : failure instanceof UnsupportedOperationException ? "UNAVAILABLE" : "READ_FAILED");
        }
    }

    @Test
    void closedSchemaMissingFormAndBudget() {
        var f = new Fixture();
        f.args.put("arbitrarySdkMethod", "unsafe");
        assertThrows(IllegalArgumentException.class, f::call);
        f = new Fixture();
        f.args.put("referenceFormGuid", "missing");
        error(f.call(), "NOT_FOUND");
        var b = snapshot(7);
        var p = Collections.nCopies(10002, new Point2(0, 0));
        var big = new WarpAuthoringSnapshot(
                b.deformerId(),
                b.sourceGuid(),
                1,
                5000,
                false,
                b.axes(),
                b.cells(),
                List.of(new WarpAuthoringSnapshot.Form("ref", p), new WarpAuthoringSnapshot.Form("target", p)));
        error(new Fixture(big).call(), "BUDGET_EXCEEDED");
    }

    @Test
    void irregularReferenceAndTruncationRemainExplicitThroughOutputSchema() {
        var bad = Collections.nCopies(4, new Point2(0, 0));
        var f = new Fixture(McpWarpQualityTest.snapshot(bad, bad));
        f.args.put("maxFindings", 1);
        var r = f.call();
        assertEquals(true, r.get("ok"), r.toString());
        assertEquals(true, r.get("analysisComplete"));
        assertEquals(false, r.get("comparisonComplete"));
        assertEquals(true, r.get("findingsTruncated"));
        var stats = (Map<?, ?>) r.get("statistics");
        assertEquals(2L, stats.get("findingCount"));
        assertEquals(1, stats.get("retainedFindingCount"));
        assertEquals(1L, stats.get("skippedOrientationComparisons"));
    }

    @Test
    void oversizedFindingOutputFailsWithoutReturningPartialVerdict() {
        var b = snapshot(7);
        int columns = 60;
        var bad = Collections.nCopies(2 * (columns + 1), new Point2(0, 0));
        String ref = "r".repeat(4096), target = "t".repeat(4096);
        var s = new WarpAuthoringSnapshot(
                b.deformerId(),
                b.sourceGuid(),
                1,
                columns,
                false,
                b.axes(),
                List.of(
                        new WarpAuthoringSnapshot.Cell(ref, List.of(new WarpAuthoringSnapshot.Coordinate("axis", 0))),
                        new WarpAuthoringSnapshot.Cell(
                                target, List.of(new WarpAuthoringSnapshot.Coordinate("axis", 1)))),
                List.of(new WarpAuthoringSnapshot.Form(ref, bad), new WarpAuthoringSnapshot.Form(target, bad)));
        var f = new Fixture(s);
        f.args.put("referenceFormGuid", ref);
        f.args.put("formGuids", List.of(target));
        error(f.call(), "BUDGET_EXCEEDED");
    }

    @Test
    void landmarkPlannerOutputCanBeDiagnosedBeforeSeparateWrite() {
        var snapshot = McpWarpRigDomainTest.snapshot(7);
        var edits = McpWarpRigPlan.edits(
                snapshot,
                McpWarpRigPlanTest.axes(true),
                List.of(new McpLandmarkRigPlan.Corner(
                        Map.of("x-guid", true, "y-guid", true), List.of(new McpLandmarkRigPlan.Anchor(1, 2, 3)))));
        var f = new Fixture(snapshot);
        String reference = "f1.0:0.0";
        f.args.put("referenceFormGuid", reference);
        f.args.put(
                "formGuids", edits.stream().map(e -> (String) e.get("formGuid")).toList());
        f.args.put("proposedEdits", edits);
        var r = f.call();
        assertEquals(true, r.get("ok"), r.toString());
        assertEquals(8, r.get("proposedEditCount"));
        assertEquals(true, r.get("analysisComplete"));
        assertEquals(18L, ((Map<?, ?>) r.get("statistics")).get("cellSamples"));
        assertEquals(snapshot, f.live);
        assertEquals(2, f.reads);
    }

    private static final class Fixture {
        long now;
        int reads;
        boolean changeOnSecondRead;
        RuntimeException failure;
        Optional<DeformerId> parent = Optional.empty();
        WarpAuthoringSnapshot live = snapshot(7);
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
        final WarpDeformer rotation = proxy(WarpDeformer.class, (name, a) -> switch (name) {
            case "authoringSnapshot" -> {
                reads++;
                if (failure != null) throw failure;
                yield changeOnSecondRead && reads == 2 ? snapshot(8) : live;
            }
            case "parentPartId" -> Optional.empty();
            case "parentDeformerId" -> parent;
            default -> throw new AssertionError("Read-only planner unexpectedly called " + name);
        });
        final CubismModel model = proxy(CubismModel.class, (name, a) -> {
            if (name.equals("deformers"))
                return proxy(Deformers.class, (n, v) -> {
                    if (n.equals("find")) return rotation;
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
        final McpToolCatalog tools = new McpWarpValidationDomain(
                        store, execution, () -> new McpStoredAuthoringDomain.Context(model, Map.copyOf(binding)))
                .tools();
        final Map<String, Object> args = new LinkedHashMap<>();

        Fixture() {
            this(snapshot(7));
        }

        Fixture(WarpAuthoringSnapshot initial) {
            live = initial;
            var row = new LinkedHashMap<String, Object>();
            row.put("kind", "parent_space");
            row.put("parentPartId", null);
            row.put("parentDeformerId", null);
            var entry = store.put(
                    McpRequestRegistry.currentSessionId(),
                    binding,
                    "warp_deformer",
                    "warp",
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
                    "warp",
                    "snapshotId",
                    entry.snapshotId(),
                    "referenceFormGuid",
                    "ref",
                    "formGuids",
                    List.of("target"),
                    "minimumAbsJacobianRatio",
                    1e-8,
                    "maxFindings",
                    200));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> call() {
            return (Map<String, Object>)
                    tools.call(McpWarpValidationDomain.VALIDATE, args).get("structuredContent");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.util.function.BiFunction<String, Object[], Object> handler) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> handler.apply(m.getName(), a));
    }
}
