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

class McpRigTransferDomainTest {
    @Test
    void allThreeKindsProduceOnlyATargetWriteRequestWithPreservedDefaults() {
        for (String kind : List.of("art_mesh", "warp_deformer", "rotation_deformer")) {
            var f = new Fixture(kind);
            var r = f.call();
            assertEquals(true, r.get("ok"), r.toString());
            assertEquals(false, r.get("applied"));
            assertEquals(false, r.get("operationIdReserved"));
            assertEquals("NOT_CHECKED", r.get("writeAdmission"));
            assertEquals("VERIFIED_AT_PLAN_ONLY", r.get("sourceReadScope"));
            assertEquals(2, f.sourceReads);
            assertEquals(2, f.targetReads);
            var request = (Map<?, ?>) r.get("writeRequest");
            var targets = (List<?>) request.get("targets");
            assertEquals(1, targets.size());
            var target = (Map<?, ?>) targets.get(0);
            assertEquals("target", target.get("objectId"));
            var edits = (List<?>) target.get("edits");
            assertEquals(2, edits.size());
            assertTrue(edits.stream()
                    .allMatch(e -> !((Map<?, ?>) e).get("formGuid").equals("tf1")));
            assertEquals(f.targetLive, McpRigTransferPlanTest.snapshot(kind, true));
            assertEquals(f.sourceLive, McpRigTransferPlanTest.snapshot(kind, false));
            assertEquals(
                    McpOperationEffect.READ,
                    f.tools.registration(McpRigTransferDomain.PLAN).effect());
            assertFalse(f.tools.registration(McpRigTransferDomain.PLAN).transactionEligible());
            McpKeyformWritePlan.prepare(f.targetLive, edits);
        }
    }

    @Test
    void laterSourceChangesDoNotMutateTheExplicitHistoricalTargetPlan() {
        var f = new Fixture("warp_deformer");
        var r = f.call();
        assertEquals(true, r.get("ok"), r.toString());
        var before = dev.turboism.sdk.json.Json.stringify(r);
        f.sourceLive = McpKeyformContinuityTest.warp();
        assertEquals(before, dev.turboism.sdk.json.Json.stringify(r));
        assertEquals("VERIFIED_AT_PLAN_ONLY", r.get("sourceReadScope"));
    }

    @Test
    void bothSourceAndTargetAreVerifiedAgainAfterPlanning() {
        var f = new Fixture("warp_deformer");
        f.changeSourceOnSecond = true;
        error(f.call(), "STALE_STATE");
        assertEquals(2, f.sourceReads);
        f = new Fixture("warp_deformer");
        f.changeTargetOnSecond = true;
        error(f.call(), "STALE_STATE");
        assertEquals(2, f.targetReads);
        f = new Fixture("warp_deformer");
        f.sourceParent = Optional.of(new DeformerId("changed"));
        error(f.call(), "STALE_STATE");
        f = new Fixture("warp_deformer");
        f.binding.put("historyRevision", 2L);
        error(f.call(), "STALE_STATE");
        f = new Fixture("warp_deformer");
        f.args.put("modelId", "other");
        error(f.call(), "STALE_STATE");
    }

    @Test
    void sourceAndTargetCannotAliasOrUseCrossSessionExpiredOrWrongKindSnapshots() {
        var f = new Fixture("warp_deformer");
        f.args.put("target", f.args.get("source"));
        error(f.call(), "INVALID_ARGUMENT");
        f = new Fixture("warp_deformer");
        error(McpRequestRegistry.inSession("other", f::call), "NOT_FOUND");
        f.now = McpAuthoringSnapshotStore.TTL_NANOS;
        error(f.call(), "NOT_FOUND");
        f = new Fixture("warp_deformer");
        f.args.put("kind", "art_mesh");
        error(f.call(), "STALE_STATE");
    }

    @Test
    void completeExplicitMappingsNativeFloatIdentityAndStrictSchemasAreRequired() {
        var f = new Fixture("warp_deformer");
        f.args.put(
                "axes",
                List.of(Map.of(
                        "sourceParameterGuid",
                        "axis",
                        "targetParameterGuid",
                        "mapped",
                        "keys",
                        List.of(
                                Map.of("sourceValue", -1, "targetValue", 10),
                                Map.of("sourceValue", 0, "targetValue", 20),
                                Map.of("sourceValue", 1.0000000001, "targetValue", 30)))));
        error(f.call(), "INVALID_ARGUMENT");
        f = new Fixture("warp_deformer");
        f.args.put("sourcePointIndices", List.of(0, 1, 2, 2));
        error(f.call(), "INVALID_ARGUMENT");
        f = new Fixture("warp_deformer");
        f.args.put("path", "/secret/model");
        var bad = f;
        assertThrows(IllegalArgumentException.class, bad::call);
        f = new Fixture("rotation_deformer");
        f.args.put("reflectionPolicy", "GUESS_FROM_NAME");
        bad = f;
        assertThrows(IllegalArgumentException.class, bad::call);
        f = new Fixture("warp_deformer");
        f.args.put("deltaTransform", Map.of("xx", 1, "xy", 2, "yx", 2, "yy", 4));
        error(f.call(), "INVALID_ARGUMENT");
    }

    @Test
    void permissionAndNativeReadFailuresNeverLeakDetailsOrPartialWriteRequests() {
        for (RuntimeException failure : List.of(
                new SecurityException("secret path"),
                new UnsupportedOperationException("secret path"),
                new IllegalStateException("secret path"))) {
            var f = new Fixture("warp_deformer");
            f.failure = failure;
            error(
                    f.call(),
                    failure instanceof SecurityException
                            ? "PERMISSION_DENIED"
                            : failure instanceof UnsupportedOperationException ? "UNAVAILABLE" : "READ_FAILED");
        }
    }

    @Test
    void planningIsRepeatableAndDoesNotReserveOrConsumeSnapshotOrOperationIdentity() {
        var f = new Fixture("warp_deformer");
        var first = f.call();
        var second = f.call();
        assertEquals(first, second);
        assertEquals(4, f.sourceReads);
        assertEquals(4, f.targetReads);
        f.store.get(
                (String) ((Map<?, ?>) f.args.get("source")).get("snapshotId"), McpRequestRegistry.currentSessionId());
        f.store.get(
                (String) ((Map<?, ?>) f.args.get("target")).get("snapshotId"), McpRequestRegistry.currentSessionId());
    }

    private static void error(Map<String, Object> r, String code) {
        assertEquals(false, r.get("ok"), r.toString());
        assertEquals(code, ((Map<?, ?>) r.get("error")).get("code"));
        assertFalse(r.containsKey("writeRequest"));
        assertFalse(r.toString().contains("secret"));
    }

    private static final class Fixture {
        long now;
        int sourceReads, targetReads;
        boolean changeSourceOnSecond, changeTargetOnSecond;
        RuntimeException failure;
        Object sourceLive, targetLive;
        Optional<DeformerId> sourceParent = Optional.empty();
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

        Fixture(String kind) {
            sourceLive = McpRigTransferPlanTest.snapshot(kind, false);
            targetLive = McpRigTransferPlanTest.snapshot(kind, true);
            Class<?> type = kind.equals("art_mesh")
                    ? Drawable.class
                    : kind.equals("warp_deformer") ? WarpDeformer.class : RotationDeformer.class;
            var source = handle(type, true);
            var target = handle(type, false);
            var model = proxy(CubismModel.class, (name, a) -> switch (name) {
                case "drawables" ->
                    proxy(Drawables.class, (n, b) -> {
                        if (n.equals("find")) return ((ArtMeshId) b[0]).value().equals("source") ? source : target;
                        throw new AssertionError(n);
                    });
                case "deformers" ->
                    proxy(Deformers.class, (n, b) -> {
                        if (n.equals("find")) return ((DeformerId) b[0]).value().equals("source") ? source : target;
                        throw new AssertionError(n);
                    });
                default -> throw new AssertionError("Read-only planning called " + name);
            });
            var execution = new McpExecutionBridge(new UiScheduler() {
                public Registration runOnUiThread(Runnable work) {
                    work.run();
                    return () -> {};
                }

                public Registration runOnUiThreadLater(Runnable work, Duration delay) {
                    throw new AssertionError();
                }
            });
            tools = new McpRigTransferDomain(
                            store, execution, () -> new McpStoredAuthoringDomain.Context(model, Map.copyOf(binding)))
                    .tools();
            var row = new LinkedHashMap<String, Object>();
            row.put("kind", "parent_space");
            row.put("parentPartId", null);
            row.put("parentDeformerId", null);
            var se = store.put(
                    McpRequestRegistry.currentSessionId(),
                    binding,
                    kind,
                    "source",
                    "source-guid",
                    sourceLive,
                    List.of(row),
                    1000);
            var te = store.put(
                    McpRequestRegistry.currentSessionId(),
                    binding,
                    kind,
                    "target",
                    "target-guid",
                    targetLive,
                    List.of(row),
                    1000);
            args.putAll(Map.of(
                    "documentId",
                    "doc",
                    "modelId",
                    "model",
                    "operationId",
                    "transfer",
                    "kind",
                    kind,
                    "policy",
                    "REPLACE_FROM_SOURCE_DEFAULT_DELTAS",
                    "source",
                    Map.of("objectId", "source", "snapshotId", se.snapshotId()),
                    "target",
                    Map.of("objectId", "target", "snapshotId", te.snapshotId()),
                    "axes",
                    List.of(Map.of(
                            "sourceParameterGuid",
                            "axis",
                            "targetParameterGuid",
                            "mapped",
                            "keys",
                            List.of(
                                    Map.of("sourceValue", -1, "targetValue", 10),
                                    Map.of("sourceValue", 0, "targetValue", 20),
                                    Map.of("sourceValue", 1, "targetValue", 30)))),
                    "deltaTransform",
                    Map.of("xx", -1, "xy", 0, "yx", 0, "yy", 1)));
            if (kind.equals("rotation_deformer")) {
                args.put("angleGain", -1);
                args.put("reflectionPolicy", "RELATIVE_XOR");
            } else args.put("sourcePointIndices", List.of(3, 2, 1, 0));
        }

        Object handle(Class<?> type, boolean source) {
            return proxy(type, (name, a) -> switch (name) {
                case "authoringSnapshot" -> {
                    if (failure != null) throw failure;
                    if (source) {
                        sourceReads++;
                        if (changeSourceOnSecond && sourceReads == 2) yield targetLive;
                        yield sourceLive;
                    }
                    targetReads++;
                    if (changeTargetOnSecond && targetReads == 2) yield sourceLive;
                    yield targetLive;
                }
                case "parentPartId" -> Optional.empty();
                case "parentDeformerId" -> source ? sourceParent : Optional.empty();
                default -> throw new AssertionError("Read-only planning called " + name);
            });
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> call() {
            return (Map<String, Object>)
                    tools.call(McpRigTransferDomain.PLAN, args).get("structuredContent");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.util.function.BiFunction<String, Object[], Object> fn) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> fn.apply(m.getName(), a));
    }
}
