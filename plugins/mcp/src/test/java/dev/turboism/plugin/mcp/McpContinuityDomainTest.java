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

class McpContinuityDomainTest {
    @Test
    void threeKindsUseReadOnlyNativeSnapshotsAndClosedOutputSchema() {
        for (Object s : List.of(
                McpKeyformContinuityTest.warp(),
                McpMeshRigPlanTest.fromWarp(McpKeyformContinuityTest.warp()),
                McpKeyformContinuityTest.rotation())) {
            var f = new Fixture(s);
            var r = f.call();
            assertEquals(true, r.get("ok"), r.toString());
            assertEquals(2, f.reads);
            assertEquals(s, f.live);
            var stats = (Map<?, ?>) r.get("statistics");
            if (!f.kind.equals("rotation_deformer")) {
                assertNull(stats.get("maximumAngleSlopeChange"));
                assertNull(stats.get("maximumScaleSlopeChange"));
            }
            assertEquals(1, ((List<?>) r.get("axes")).size());
            assertEquals(true, r.get("analysisComplete"));
            assertEquals(
                    McpOperationEffect.READ,
                    f.tools.registration(McpContinuityDomain.VALIDATE).effect());
            assertFalse(f.tools.registration(McpContinuityDomain.VALIDATE).transactionEligible());
            assertTrue(((List<?>) r.get("notImplementedChecks")).contains("NATIVE_INTERPOLATION_CONTINUITY"));
            assertEquals(
                    f.kind.equals("rotation_deformer") ? "FINDINGS" : "NO_FINDINGS_FOR_IMPLEMENTED_CHECKS",
                    r.get("verdict"));
        }
    }

    @Test
    void rigProposalCanBeDiagnosedBeforeWritingAndNeverMutatesLiveState() {
        var f = new Fixture(McpKeyformContinuityTest.warp());
        f.args.put(
                "proposedEdits",
                List.of(Map.of(
                        "formGuid",
                        "f2",
                        "coordinates",
                        List.of(Map.of("parameterGuid", "axis", "value", 1)),
                        "controlPoints",
                        List.of(
                                Map.of("x", 5, "y", 0),
                                Map.of("x", 6, "y", 0),
                                Map.of("x", 5, "y", 1),
                                Map.of("x", 6, "y", 1)))));
        var result = f.call();
        assertEquals(true, result.get("ok"), result.toString());
        assertEquals("FINDINGS", result.get("verdict"));
        assertEquals(1, result.get("proposedEditCount"));
        assertEquals(4, ((List<?>) result.get("findings")).size());
        assertEquals(McpKeyformContinuityTest.warp(), f.live);
        f.args.put(
                "proposedEdits",
                List.of(Map.of(
                        "formGuid",
                        "f1",
                        "coordinates",
                        List.of(Map.of("parameterGuid", "axis", "value", 0)),
                        "controlPoints",
                        Collections.nCopies(4, Map.of("x", 0, "y", 0)))));
        error(f.call(), "INVALID_ARGUMENT");
    }

    @Test
    void sourceFormAndParentChangesAreStaleEvenWithSameHistory() {
        var f = new Fixture(McpKeyformContinuityTest.warp());
        f.parent = Optional.of(new DeformerId("different"));
        error(f.call(), "STALE_STATE");
        f = new Fixture(McpKeyformContinuityTest.warp());
        f.changeOnSecond = true;
        error(f.call(), "STALE_STATE");
        assertEquals(2, f.reads);
        f = new Fixture(McpKeyformContinuityTest.warp());
        f.binding.put("historyRevision", 2L);
        error(f.call(), "STALE_STATE");
        f = new Fixture(McpKeyformContinuityTest.warp());
        f.args.put("documentId", "other");
        error(f.call(), "STALE_STATE");
    }

    @Test
    void expiryMissingTokensPermissionAndBackendFailuresNeverProduceVerdicts() {
        var f = new Fixture(McpKeyformContinuityTest.warp());
        error(McpRequestRegistry.inSession("other", f::call), "NOT_FOUND");
        f.now = McpAuthoringSnapshotStore.TTL_NANOS;
        error(f.call(), "NOT_FOUND");
        f = new Fixture(McpKeyformContinuityTest.warp());
        f.failure = new SecurityException("secret path");
        error(f.call(), "PERMISSION_DENIED");
        f = new Fixture(McpKeyformContinuityTest.warp());
        f.failure = new UnsupportedOperationException("secret path");
        error(f.call(), "UNAVAILABLE");
        f = new Fixture(McpKeyformContinuityTest.warp());
        f.failure = new IllegalStateException("secret path");
        error(f.call(), "READ_FAILED");
    }

    @Test
    void defaultAndSharedFormsRemainReadableButProposalsCannotBypassWriterProtection() {
        var w = McpKeyformContinuityTest.warp();
        var shared = new WarpAuthoringSnapshot(
                w.deformerId(),
                w.sourceGuid(),
                1,
                1,
                false,
                w.axes(),
                w.cells().stream()
                        .map(c -> new WarpAuthoringSnapshot.Cell("f1", c.coordinates()))
                        .toList(),
                List.of(w.forms().get(1)));
        var f = new Fixture(shared);
        assertEquals(true, f.call().get("ok"));
        f.args.put(
                "proposedEdits",
                List.of(Map.of(
                        "formGuid",
                        "f1",
                        "coordinates",
                        List.of(Map.of("parameterGuid", "axis", "value", 1)),
                        "controlPoints",
                        Collections.nCopies(4, Map.of("x", 0, "y", 0)))));
        error(f.call(), "INVALID_ARGUMENT");
    }

    @Test
    void malformedOrUnsupportedGridDoesNotBecomeAnEmptyPass() {
        var f = new Fixture(McpKeyformContinuityTest.warp(List.of(-1f, 1f), List.of(0f, 0f)));
        error(f.call(), "UNAVAILABLE");
        f = new Fixture(McpKeyformContinuityTest.warp());
        f.args.put("path", "/secret/file");
        var bad = f;
        assertThrows(IllegalArgumentException.class, bad::call);
        f = new Fixture(McpKeyformContinuityTest.warp());
        f.args.put(
                "thresholds",
                Map.of(
                        "maximumPositionSlopeChange",
                        Double.NaN,
                        "maximumAngleSlopeChange",
                        0,
                        "maximumScaleSlopeChange",
                        0));
        bad = f;
        assertThrows(IllegalArgumentException.class, bad::call);
    }

    @Test
    void truncatedReportRetainsFullCountAndOutputBudgetFailsClosed() {
        var f = new Fixture(McpKeyformContinuityTest.rotation());
        f.args.put("maxFindings", 1);
        var r = f.call();
        assertEquals(true, r.get("findingsTruncated"));
        assertEquals(5L, ((Map<?, ?>) r.get("statistics")).get("findingCount"));
        assertEquals(true, r.get("analysisComplete"));
        var w = McpKeyformContinuityTest.warp(List.of(-1f, 0f, 1f), List.of(0f, 0f, 1f));
        var cells = w.cells().stream()
                .map(c -> new WarpAuthoringSnapshot.Cell(c.formGuid().repeat(2048), c.coordinates()))
                .toList();
        var forms = w.forms().stream()
                .map(v -> new WarpAuthoringSnapshot.Form(
                        v.formGuid().repeat(2048),
                        Collections.nCopies(100, v.controlPoints().get(0))))
                .toList();
        f = new Fixture(
                new WarpAuthoringSnapshot(w.deformerId(), w.sourceGuid(), 1, 49, false, w.axes(), cells, forms));
        error(f.call(), "BUDGET_EXCEEDED");
    }

    private static void error(Map<String, Object> result, String code) {
        assertEquals(false, result.get("ok"), result.toString());
        assertEquals(code, ((Map<?, ?>) result.get("error")).get("code"));
        assertFalse(result.containsKey("verdict"));
        assertFalse(result.toString().contains("secret"));
    }

    private static final class Fixture {
        long now;
        int reads;
        boolean changeOnSecond;
        RuntimeException failure;
        Object live;
        Optional<DeformerId> parent = Optional.empty();
        final String kind;
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
        final McpAuthoringSnapshotStore store = new McpAuthoringSnapshotStore(() -> now);
        final McpToolCatalog tools;
        final Map<String, Object> args = new LinkedHashMap<>();

        Fixture(Object initial) {
            live = initial;
            kind = initial instanceof ArtMeshAuthoringSnapshot
                    ? "art_mesh"
                    : initial instanceof WarpAuthoringSnapshot ? "warp_deformer" : "rotation_deformer";
            Class<?> handleType = initial instanceof ArtMeshAuthoringSnapshot
                    ? Drawable.class
                    : initial instanceof WarpAuthoringSnapshot ? WarpDeformer.class : RotationDeformer.class;
            Object handle = proxy(handleType, (name, a) -> switch (name) {
                case "authoringSnapshot" -> {
                    reads++;
                    if (failure != null) throw failure;
                    if (changeOnSecond && reads == 2)
                        yield McpKeyformContinuityTest.warp(List.of(-1f, 0f, 1f), List.of(0f, 0f, 1f));
                    yield live;
                }
                case "parentPartId" -> Optional.empty();
                case "parentDeformerId" -> parent;
                default -> throw new AssertionError("Read-only diagnostics called " + name);
            });
            var model = proxy(CubismModel.class, (name, a) -> switch (name) {
                case "drawables" ->
                    proxy(Drawables.class, (n, b) -> {
                        if (n.equals("find")) return handle;
                        throw new AssertionError(n);
                    });
                case "deformers" ->
                    proxy(Deformers.class, (n, b) -> {
                        if (n.equals("find")) return handle;
                        throw new AssertionError(n);
                    });
                default -> throw new AssertionError(name);
            });
            var bridge = new McpExecutionBridge(new UiScheduler() {
                public Registration runOnUiThread(Runnable work) {
                    work.run();
                    return () -> {};
                }

                public Registration runOnUiThreadLater(Runnable work, Duration delay) {
                    throw new AssertionError();
                }
            });
            tools = new McpContinuityDomain(
                            store, bridge, () -> new McpStoredAuthoringDomain.Context(model, Map.copyOf(binding)))
                    .tools();
            var parentRow = new LinkedHashMap<String, Object>();
            parentRow.put("kind", "parent_space");
            parentRow.put("parentPartId", null);
            parentRow.put("parentDeformerId", null);
            var token = store.put(
                    McpRequestRegistry.currentSessionId(),
                    binding,
                    kind,
                    "object",
                    "source",
                    initial,
                    List.of(parentRow),
                    1000);
            args.putAll(Map.of(
                    "documentId",
                    "doc",
                    "modelId",
                    "model",
                    "kind",
                    kind,
                    "objectId",
                    "object",
                    "snapshotId",
                    token.snapshotId(),
                    "thresholds",
                    Map.of("maximumPositionSlopeChange", 0, "maximumAngleSlopeChange", 0, "maximumScaleSlopeChange", 0),
                    "maxFindings",
                    200));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> call() {
            return (Map<String, Object>)
                    tools.call(McpContinuityDomain.VALIDATE, args).get("structuredContent");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.util.function.BiFunction<String, Object[], Object> handler) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> handler.apply(m.getName(), a));
    }
}
