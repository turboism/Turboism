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

class McpRotationRigDomainTest {
    @Test
    void previewReplacesAnglesPreservesAllOtherValuesAndIsAcceptedByWriter() {
        var f = new Fixture();
        var result = f.call();
        assertEquals(true, result.get("ok"), result.toString());
        assertEquals(false, result.get("applied"));
        assertEquals(2, f.reads);
        var request = (Map<?, ?>) result.get("writeRequest");
        var target = (Map<?, ?>) ((List<?>) request.get("targets")).get(0);
        var plan = (McpKeyformWritePlan.Rotation) McpKeyformWritePlan.prepare(f.live, target.get("edits"));
        assertEquals(f.live.forms().get(0), plan.after().forms().get(0));
        assertEquals(f.live.baseAngle(), plan.after().baseAngle());
        assertEquals(f.live.axes(), plan.after().axes());
        assertEquals(f.live.cells(), plan.after().cells());
        for (int i = 1; i < 3; i++) {
            var old = f.live.forms().get(i).value();
            var now = plan.after().forms().get(i).value();
            assertEquals(i == 1 ? -3f : 17f, now.angle());
            assertEquals(old.origin(), now.origin());
            assertEquals(old.scale(), now.scale());
            assertEquals(old.reflectedX(), now.reflectedX());
            assertEquals(old.reflectedY(), now.reflectedY());
        }
        // Replacement is stable when replanned after an earlier application.
        assertEquals(
                plan.after(),
                McpKeyformWritePlan.prepare(plan.after(), McpRotationRigPlan.edits(plan.after(), "axis-guid", .5))
                        .after());
        assertEquals(
                McpOperationEffect.READ,
                f.tools.registration(McpRotationRigDomain.PLAN).effect());
        assertFalse(f.tools.registration(McpRotationRigDomain.PLAN).transactionEligible());
    }

    @Test
    void nativeGeometryHistoryAndParentChangesRejectWithoutProducingWriteRequest() {
        var f = new Fixture();
        f.live = snapshot(9);
        error(f.call(), "STALE_STATE");
        f = new Fixture();
        f.changeOnSecondRead = true;
        error(f.call(), "STALE_STATE");
        f = new Fixture();
        f.binding.put("historyRevision", 2L);
        error(f.call(), "STALE_STATE");
        f = new Fixture();
        f.parent = Optional.of(new DeformerId("new-parent"));
        error(f.call(), "STALE_STATE");
        f = new Fixture();
        f.args.put("documentId", "other");
        error(f.call(), "STALE_STATE");
    }

    @Test
    void sessionAndExpiryDoNotLeakCachedPlan() {
        var f = new Fixture();
        error(McpRequestRegistry.inSession("other", f::call), "NOT_FOUND");
        f.now = McpAuthoringSnapshotStore.TTL_NANOS;
        error(f.call(), "NOT_FOUND");
    }

    @Test
    void errorsFailClosedAndDoNotExposeNativeDetails() {
        var f = new Fixture();
        f.failure = new SecurityException("secret native path");
        error(f.call(), "PERMISSION_DENIED");
        f = new Fixture();
        f.failure = new UnsupportedOperationException("secret native path");
        error(f.call(), "UNAVAILABLE");
        f = new Fixture();
        f.failure = new IllegalStateException("secret native path");
        error(f.call(), "READ_FAILED");
        f = new Fixture();
        f.args.put("parameterGuid", "display-id");
        error(f.call(), "UNAVAILABLE");
        f = new Fixture();
        f.args.put("policy", "ADDITIVE");
        assertThrows(IllegalArgumentException.class, f::call);
    }

    @Test
    void ambiguousIncompleteSharedAndMultiAxisGridsAreRejected() {
        var b = snapshot(7);
        var duplicate = new ArrayList<>(b.cells());
        duplicate.set(2, b.cells().get(1));
        assertThrows(
                IllegalArgumentException.class,
                () -> McpRotationRigPlan.edits(
                        new RotationAuthoringSnapshot(
                                b.deformerId(), b.sourceGuid(), b.baseAngle(), b.axes(), duplicate, b.forms()),
                        "axis-guid",
                        1));
        var shared = List.of(
                b.cells().get(0),
                b.cells().get(1),
                new RotationAuthoringSnapshot.Cell("minus", b.cells().get(2).coordinates()));
        assertThrows(
                IllegalArgumentException.class,
                () -> McpRotationRigPlan.edits(
                        new RotationAuthoringSnapshot(
                                b.deformerId(), b.sourceGuid(), b.baseAngle(), b.axes(), shared, b.forms()),
                        "axis-guid",
                        1));
        assertThrows(
                IllegalArgumentException.class,
                () -> McpRotationRigPlan.edits(
                        new RotationAuthoringSnapshot(
                                b.deformerId(),
                                b.sourceGuid(),
                                b.baseAngle(),
                                b.axes(),
                                b.cells().subList(0, 2),
                                b.forms()),
                        "axis-guid",
                        1));
        assertThrows(
                UnsupportedOperationException.class,
                () -> McpRotationRigPlan.edits(
                        new RotationAuthoringSnapshot(
                                b.deformerId(), b.sourceGuid(), b.baseAngle(), List.of(), List.of(), b.forms()),
                        "axis-guid",
                        1));
        var missingDefault = List.of(new RotationAuthoringSnapshot.Axis(
                "axis-guid", new ParameterId("display-id"), -20, 1, 20, List.of(0f, -20f, 20f)));
        assertThrows(
                IllegalArgumentException.class,
                () -> McpRotationRigPlan.edits(
                        new RotationAuthoringSnapshot(
                                b.deformerId(), b.sourceGuid(), b.baseAngle(), missingDefault, b.cells(), b.forms()),
                        "axis-guid",
                        1));
    }

    @Test
    void finiteNativeFloatAndEditBudgetsAreEnforced() {
        var b = snapshot(7);
        assertThrows(IllegalArgumentException.class, () -> McpRotationRigPlan.edits(b, "axis-guid", Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> McpRotationRigPlan.edits(b, "axis-guid", Double.MAX_VALUE));
        var edits = McpRotationRigPlan.edits(b, "axis-guid", 0);
        var plan = (McpKeyformWritePlan.Rotation) McpKeyformWritePlan.prepare(b, edits);
        assertTrue(plan.edits().stream().allMatch(e -> e.value().angle() == 7));
        var keys = new ArrayList<Float>();
        for (int i = 0; i < 66; i++) keys.add((float) i);
        var axis = new RotationAuthoringSnapshot.Axis("axis-guid", new ParameterId("display-id"), 0, 0, 65, keys);
        assertThrows(
                IllegalArgumentException.class,
                () -> McpRotationRigPlan.edits(
                        new RotationAuthoringSnapshot(
                                b.deformerId(), b.sourceGuid(), 0, List.of(axis), List.of(), b.forms()),
                        "axis-guid",
                        1));
    }

    @Test
    void accepts64TargetsAndRejectsTrueMultiAxisScope() {
        var keys = new ArrayList<Float>();
        var cells = new ArrayList<RotationAuthoringSnapshot.Cell>();
        var forms = new ArrayList<RotationAuthoringSnapshot.Form>();
        for (int i = 0; i < 65; i++) {
            keys.add((float) i);
            cells.add(cell("f" + i, i));
            forms.add(new RotationAuthoringSnapshot.Form("f" + i, new RotationDeformerForm(0, 0, 0, 1, false, false)));
        }
        var axis = new RotationAuthoringSnapshot.Axis("axis-guid", new ParameterId("display-id"), 0, 0, 64, keys);
        var b = new RotationAuthoringSnapshot(new DeformerId("r"), "s", 0, List.of(axis), cells, forms);
        assertEquals(64, McpRotationRigPlan.edits(b, "axis-guid", -1).size());
        var second =
                new RotationAuthoringSnapshot.Axis("second-guid", new ParameterId("display-id"), 0, 0, 1, List.of(0f));
        var multiCells = cells.stream()
                .map(c -> new RotationAuthoringSnapshot.Cell(
                        c.formGuid(),
                        List.of(c.coordinates().get(0), new RotationAuthoringSnapshot.Coordinate("second-guid", 0))))
                .toList();
        var multi = new RotationAuthoringSnapshot(
                b.deformerId(), b.sourceGuid(), 0, List.of(axis, second), multiCells, forms);
        assertThrows(UnsupportedOperationException.class, () -> McpRotationRigPlan.edits(multi, "axis-guid", 1));
    }

    @Test
    void multiAxisSchemaRoutesOnlyReadsAndReturnsFullTupleWriterRequest() {
        var f = new Fixture(McpRotationRigPlanTest.grid(3, 3));
        f.args.remove("parameterGuid");
        f.args.remove("nativeAnglePerParameterUnit");
        f.args.put(
                "axes",
                List.of(
                        Map.of("parameterGuid", "g2", "nativeAnglePerParameterUnit", 3),
                        Map.of("parameterGuid", "g0", "nativeAnglePerParameterUnit", 1),
                        Map.of("parameterGuid", "g1", "nativeAnglePerParameterUnit", 2)));
        var result = f.call();
        assertEquals(true, result.get("ok"), result.toString());
        assertEquals(false, result.get("applied"));
        assertEquals(2, f.reads);
        var request = (Map<?, ?>) result.get("writeRequest");
        var target = (Map<?, ?>) ((List<?>) request.get("targets")).get(0);
        var after = McpKeyformWritePlan.prepare(f.live, target.get("edits")).after();
        assertEquals(
                McpKeyformWritePlan.prepare(f.live, McpRotationRigPlan.edits(f.live, McpRotationRigPlanTest.gains(3)))
                        .after(),
                after);
        f.args.put("parameterGuid", "g0");
        assertThrows(IllegalArgumentException.class, f::call);
        f.args.remove("parameterGuid");
        f.args.put("axes", List.of(Map.of("parameterGuid", "g0", "nativeAnglePerParameterUnit", 1)));
        error(f.call(), "UNAVAILABLE");
        f.args.put("axes", List.of(Map.of("parameterGuid", "g0", "nativeAnglePerParameterUnit", "1")));
        assertThrows(IllegalArgumentException.class, f::call);
    }

    static RotationAuthoringSnapshot snapshot(float referenceAngle) {
        return new RotationAuthoringSnapshot(
                new DeformerId("rotation"),
                "source",
                19,
                List.of(new RotationAuthoringSnapshot.Axis(
                        "axis-guid", new ParameterId("display-id"), -20, 0, 20, List.of(20f, 0f, -20f))),
                List.of(cell("default", 0), cell("minus", -20), cell("plus", 20)),
                List.of(
                        new RotationAuthoringSnapshot.Form(
                                "default", new RotationDeformerForm(referenceAngle, 1, 2, 1, false, false)),
                        new RotationAuthoringSnapshot.Form("minus", new RotationDeformerForm(90, 3, 4, 2, true, false)),
                        new RotationAuthoringSnapshot.Form(
                                "plus", new RotationDeformerForm(-90, 5, 6, 3, false, true))));
    }

    private static RotationAuthoringSnapshot.Cell cell(String guid, float key) {
        return new RotationAuthoringSnapshot.Cell(
                guid, List.of(new RotationAuthoringSnapshot.Coordinate("axis-guid", key)));
    }

    private static void error(Map<String, Object> result, String code) {
        assertEquals(false, result.get("ok"), result.toString());
        assertEquals(code, ((Map<?, ?>) result.get("error")).get("code"));
        assertFalse(result.containsKey("writeRequest"));
        assertFalse(result.toString().contains("secret native path"));
    }

    private static final class Fixture {
        long now;
        int reads;
        boolean changeOnSecondRead;
        RuntimeException failure;
        Optional<DeformerId> parent = Optional.empty();
        RotationAuthoringSnapshot live;
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
        final RotationDeformer rotation = proxy(RotationDeformer.class, (name, a) -> switch (name) {
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
        final McpToolCatalog tools = new McpRotationRigDomain(
                        store, execution, () -> new McpStoredAuthoringDomain.Context(model, Map.copyOf(binding)))
                .tools();
        final Map<String, Object> args = new LinkedHashMap<>();

        Fixture() {
            this(snapshot(7));
        }

        Fixture(RotationAuthoringSnapshot initial) {
            live = initial;
            var row = new LinkedHashMap<String, Object>();
            row.put("kind", "parent_space");
            row.put("parentPartId", null);
            row.put("parentDeformerId", null);
            var entry = store.put(
                    McpRequestRegistry.currentSessionId(),
                    binding,
                    "rotation_deformer",
                    "rotation",
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
                    "rotation",
                    "snapshotId",
                    entry.snapshotId(),
                    "operationId",
                    "proposed-write",
                    "parameterGuid",
                    "axis-guid",
                    "nativeAnglePerParameterUnit",
                    .5,
                    "policy",
                    "REPLACE_ANGLE_FROM_DEFAULT"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> call() {
            return (Map<String, Object>)
                    tools.call(McpRotationRigDomain.PLAN, args).get("structuredContent");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.util.function.BiFunction<String, Object[], Object> handler) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> handler.apply(m.getName(), a));
    }
}
