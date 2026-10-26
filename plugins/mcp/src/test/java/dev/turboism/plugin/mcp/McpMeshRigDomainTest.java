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

class McpMeshRigDomainTest {
    @Test
    void threeAxesAndAllTwentyResidualsRemainReadOnlyAndWriterReady() {
        var f = new Fixture(McpLandmarkThreeAxisPlanTest.mesh(McpLandmarkThreeAxisPlanTest.grid(3, 3)));
        f.args.put("axes", McpLandmarkThreeAxisPlanTest.axisJson("vertexIndex"));
        f.args.put("cornerCorrections", McpLandmarkThreeAxisPlanTest.cornerJson("vertexIndex"));
        var result = f.call();
        assertEquals(true, result.get("ok"), result.toString());
        assertEquals(false, result.get("applied"));
        assertEquals(20, ((Number) result.get("cornerCorrectionCount")).intValue());
        assertEquals(2, f.reads);
        var request = (Map<?, ?>) result.get("writeRequest");
        var target = (Map<?, ?>) ((List<?>) request.get("targets")).get(0);
        var plan = (McpKeyformWritePlan.Mesh) McpKeyformWritePlan.prepare(f.live, target.get("edits"));
        assertEquals(26, plan.edits().size());
        var overflow = new ArrayList<>(McpLandmarkThreeAxisPlanTest.cornerJson("vertexIndex"));
        overflow.add(overflow.get(0));
        f.args.put("cornerCorrections", overflow);
        assertThrows(IllegalArgumentException.class, f::call);
    }

    @Test
    void previewIsReadOnlyClosedAndWriterReady() {
        var f = new Fixture();
        var result = f.call();
        assertEquals(true, result.get("ok"), result.toString());
        assertEquals(false, result.get("applied"));
        assertEquals(2, f.reads);
        var request = (Map<?, ?>) result.get("writeRequest");
        var target = (Map<?, ?>) ((List<?>) request.get("targets")).get(0);
        var plan = (McpKeyformWritePlan.Mesh) McpKeyformWritePlan.prepare(f.live, target.get("edits"));
        assertEquals(8, plan.edits().size());
        assertEquals(
                McpOperationEffect.READ,
                f.tools.registration(McpMeshRigDomain.PLAN).effect());
        assertFalse(f.tools.registration(McpMeshRigDomain.PLAN).transactionEligible());
    }

    @Test
    void fullNativeStateParentsHistoryAndPostReadAreVerified() {
        var f = new Fixture();
        f.live = snapshot(8);
        error(f.call(), "STALE_STATE");
        f = new Fixture();
        f.parent = Optional.of(new DeformerId("new-parent"));
        error(f.call(), "STALE_STATE");
        f = new Fixture();
        f.binding.put("historyRevision", 2L);
        error(f.call(), "STALE_STATE");
        f = new Fixture();
        f.changeOnSecondRead = true;
        error(f.call(), "STALE_STATE");
        f = new Fixture();
        f.args.put("modelId", "other");
        error(f.call(), "STALE_STATE");
    }

    @Test
    void expiredCrossSessionAndNativeReadFailuresDoNotProduceRequests() {
        var f = new Fixture();
        error(McpRequestRegistry.inSession("other", f::call), "NOT_FOUND");
        f.now = McpAuthoringSnapshotStore.TTL_NANOS;
        error(f.call(), "NOT_FOUND");
        f = new Fixture();
        f.failure = new SecurityException("secret path");
        error(f.call(), "PERMISSION_DENIED");
        f = new Fixture();
        f.failure = new UnsupportedOperationException("secret path");
        error(f.call(), "UNAVAILABLE");
        f = new Fixture();
        f.failure = new IllegalStateException("secret path");
        error(f.call(), "READ_FAILED");
    }

    @Test
    void malformedAnchorsAndPoliciesAreRejected() {
        var f = new Fixture();
        f.args.put("policy", "ADD_TO_EXISTING");
        assertThrows(IllegalArgumentException.class, f::call);
        f = new Fixture();
        f.args.put(
                "axes",
                List.of(Map.of("parameterGuid", "x-guid", "negativeAnchors", List.of(), "positiveAnchors", List.of())));
        assertThrows(IllegalArgumentException.class, f::call);
    }

    @Test
    void oversizedSerializedPreviewNeverReturnsAnExecutableRequest() {
        var keys = new ArrayList<Float>();
        var cells = new ArrayList<WarpAuthoringSnapshot.Cell>();
        var forms = new ArrayList<WarpAuthoringSnapshot.Form>();
        var points = List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1), new Point2(1, 1));
        for (int key = -32; key <= 32; key++) {
            keys.add((float) key);
            String guid = "long-form-" + key + "x".repeat(4000);
            cells.add(
                    new WarpAuthoringSnapshot.Cell(guid, List.of(new WarpAuthoringSnapshot.Coordinate("x-guid", key))));
            forms.add(new WarpAuthoringSnapshot.Form(guid, points));
        }
        var b = new WarpAuthoringSnapshot(
                new DeformerId("warp"),
                "source",
                1,
                1,
                false,
                List.of(new WarpAuthoringSnapshot.Axis("x-guid", new ParameterId("x"), -32, 0, 32, keys)),
                cells,
                forms);
        var f = new Fixture(McpMeshRigPlanTest.fromWarp(b));
        f.args.put(
                "axes",
                List.of(Map.of(
                        "parameterGuid",
                        "x-guid",
                        "negativeAnchors",
                        List.of(Map.of("vertexIndex", 0, "dx", 0, "dy", 0)),
                        "positiveAnchors",
                        List.of(Map.of("vertexIndex", 0, "dx", 0, "dy", 0)))));
        error(f.call(), "BUDGET_EXCEEDED");
    }

    @Test
    void cornerCorrectionsAreTypedAndReportPlanCount() {
        var f = new Fixture();
        var correction = Map.of(
                "directions",
                List.of(
                        Map.of("parameterGuid", "y-guid", "side", "POSITIVE"),
                        Map.of("parameterGuid", "x-guid", "side", "NEGATIVE")),
                "anchors",
                List.of(Map.of("vertexIndex", 1, "dx", 2, "dy", 3)));
        f.args.put("cornerCorrections", List.of(correction));
        var result = f.call();
        assertEquals(true, result.get("ok"), result.toString());
        assertEquals(1, ((Number) result.get("cornerCorrectionCount")).intValue());
        f.args.put("cornerCorrections", List.of(correction, correction));
        error(f.call(), "INVALID_ARGUMENT");
        f.args.put("cornerCorrections", List.of());
        assertEquals(0, ((Number) f.call().get("cornerCorrectionCount")).intValue());
        var invalid = new HashMap<String, Object>(correction);
        invalid.put("arbitrarySdkMethod", "write");
        f.args.put("cornerCorrections", List.of(invalid));
        assertThrows(IllegalArgumentException.class, f::call);
    }

    static ArtMeshAuthoringSnapshot snapshot(int identity) {
        return McpMeshRigPlanTest.fromWarp(McpWarpRigDomainTest.snapshot(identity));
    }

    static List<Map<String, Object>> axisJson() {
        return McpWarpRigPlanTest.axes(true).stream()
                .map(a -> Map.<String, Object>of(
                        "parameterGuid",
                        a.guid(),
                        "negativeAnchors",
                        anchorJson(a.negative()),
                        "positiveAnchors",
                        anchorJson(a.positive())))
                .toList();
    }

    static List<Map<String, Object>> anchorJson(List<McpLandmarkRigPlan.Anchor> anchors) {
        return anchors.stream()
                .map(a -> Map.<String, Object>of("vertexIndex", a.index(), "dx", a.dx(), "dy", a.dy()))
                .toList();
    }

    static void error(Map<String, Object> result, String code) {
        assertEquals(false, result.get("ok"), result.toString());
        assertEquals(code, ((Map<?, ?>) result.get("error")).get("code"));
        assertFalse(result.containsKey("writeRequest"));
        assertFalse(result.toString().contains("secret path"));
    }

    private static final class Fixture {
        long now;
        int reads;
        boolean changeOnSecondRead;
        RuntimeException failure;
        Optional<DeformerId> parent = Optional.empty();
        ArtMeshAuthoringSnapshot live = snapshot(7);
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
        final Drawable rotation = proxy(Drawable.class, (name, a) -> switch (name) {
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
            if (name.equals("drawables"))
                return proxy(Drawables.class, (n, v) -> {
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
        final McpToolCatalog tools = new McpMeshRigDomain(
                        store, execution, () -> new McpStoredAuthoringDomain.Context(model, Map.copyOf(binding)))
                .tools();
        final Map<String, Object> args = new LinkedHashMap<>();

        Fixture() {
            this(snapshot(7));
        }

        Fixture(ArtMeshAuthoringSnapshot initial) {
            live = initial;
            var row = new LinkedHashMap<String, Object>();
            row.put("kind", "parent_space");
            row.put("parentPartId", null);
            row.put("parentDeformerId", null);
            var entry = store.put(
                    McpRequestRegistry.currentSessionId(),
                    binding,
                    "art_mesh",
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
                    "operationId",
                    "proposed-write",
                    "axes",
                    axisJson(),
                    "policy",
                    "REPLACE_POSITIONS_FROM_DEFAULT"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> call() {
            return (Map<String, Object>) tools.call(McpMeshRigDomain.PLAN, args).get("structuredContent");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.util.function.BiFunction<String, Object[], Object> handler) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> handler.apply(m.getName(), a));
    }
}
