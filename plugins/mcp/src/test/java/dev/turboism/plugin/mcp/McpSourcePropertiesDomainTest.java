package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.*;
import dev.turboism.sdk.cubism.history.*;
import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.transaction.*;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

class McpSourcePropertiesDomainTest {
    @Test
    void propertySnapshotBudgetAndDuplicateObjectBatchRejectBeforeMutation() {
        var f = new Fixture();
        var args = f.readArgs("mesh");
        var existing = f.store.get((String) args.get("authoringSnapshotId"), McpRequestRegistry.currentSessionId());
        var oversized = f.store.put(
                McpRequestRegistry.currentSessionId(),
                f.binding(),
                "art_mesh",
                "mesh",
                "source",
                existing.nativeSnapshot(),
                existing.rows(),
                McpAuthoringSnapshotStore.MAX_ENTRY_BYTES);
        args.put("authoringSnapshotId", oversized.snapshotId());
        assertEquals(
                "BUDGET_EXCEEDED", f.call(McpSourcePropertiesDomain.READ, args).get("error"));
        assertEquals(0, f.writes);
        var g = new Fixture();
        var target = g.target("mesh", Map.of("visible", false));
        var output = g.write(g.request("duplicate", target, target));
        assertEquals("INVALID_ARGUMENT", output.get("code"));
        assertEquals(0, g.transactions);
        assertEquals(0, g.writes);
    }

    @Test
    void sourceReadSupportsThreeKindsWithoutCurrentFormOrEffectiveStateCalls() {
        var f = new Fixture();
        for (String id : List.of("mesh", "warp", "rotation")) {
            var read = f.read(id);
            assertEquals(true, read.get("ok"), read.toString());
            assertEquals("source_object", read.get("scope"));
            var values = (Map<?, ?>) read.get("properties");
            assertEquals(true, values.get("visible"));
            assertEquals(false, values.get("locked"));
            assertEquals(id.equals("mesh"), values.containsKey("culling"));
            assertEquals(0, f.writes);
        }
    }

    @Test
    void threeObjectBatchWritesOnlyExplicitPropertiesAndHasOneTransactionReceipt() {
        var f = new Fixture();
        var original = Map.copyOf(f.entities);
        var request = f.request(
                "three",
                f.target("mesh", Map.of("visible", false, "culling", true, "locked", true)),
                f.target("warp", Map.of("visible", false)),
                f.target("rotation", Map.of("locked", true)));
        var output = f.write(request);
        assertEquals("APPLIED", output.get("outcome"), output.toString());
        assertEquals(true, output.get("readbackVerified"));
        assertNotNull(output.get("receipt"));
        assertEquals(1, f.transactions);
        assertEquals(5, f.writes);
        assertFalse(f.entities.get("mesh").visible);
        assertTrue(f.entities.get("mesh").culling);
        assertTrue(f.entities.get("mesh").locked);
        assertFalse(f.entities.get("warp").visible);
        assertFalse(f.entities.get("warp").locked);
        assertTrue(f.entities.get("rotation").visible);
        assertTrue(f.entities.get("rotation").locked);
        for (String id : original.keySet()) assertEquals(original.get(id).nativeState, f.entities.get(id).nativeState);
        assertEquals(output, f.write(request));
        assertEquals(output, f.result("three"));
        assertEquals(5, f.writes);
        var changed = Map.copyOf(f.entities);
        f.restore(original);
        f.revision++;
        assertEquals(original, f.entities);
        f.restore(changed);
        f.revision++;
        assertEquals(changed, f.entities);
        assertEquals(output, f.result("three"));
    }

    @Test
    void propertyChangesAtSameRevisionAndNativeParentHistoryChangesRejectBeforeTransaction() {
        for (String change : List.of("flags", "native", "parent", "history", "document")) {
            var f = new Fixture();
            var request = f.request("stale", f.target("mesh", Map.of("visible", false)));
            var e = f.entities.get("mesh");
            switch (change) {
                case "flags" -> f.entities.put("mesh", new Entity(e.nativeState, e.visible, true, e.culling, e.parent));
                case "native" ->
                    f.entities.put(
                            "mesh",
                            new Entity(
                                    McpGeometryQualityTest.mesh(
                                            McpGeometryQualityTest.UNIT,
                                            McpGeometryQualityTest.TRI,
                                            McpGeometryQualityTest.UNIT,
                                            McpGeometryQualityTest.points(0, 0, 2, 0, 0, 1)),
                                    e.visible,
                                    e.locked,
                                    e.culling,
                                    e.parent));
                case "parent" ->
                    f.entities.put("mesh", new Entity(e.nativeState, e.visible, e.locked, e.culling, "other"));
                case "history" -> f.revision++;
                default -> f.document = "other";
            }
            assertEquals("STALE_STATE", f.write(request).get("code"), change);
            assertEquals(0, f.transactions);
            assertEquals(0, f.writes);
        }
    }

    @Test
    void cannotReadPropertiesFromChangedAuthoringSnapshotOrWrongTypeToken() {
        var f = new Fixture();
        var args = f.readArgs("mesh");
        var e = f.entities.get("mesh");
        f.entities.put("mesh", new Entity(e.nativeState, e.visible, e.locked, e.culling, "changed"));
        assertEquals("STALE_STATE", f.call(McpSourcePropertiesDomain.READ, args).get("error"));
        var g = new Fixture();
        args = g.readArgs("mesh");
        args.put("kind", "warp_deformer");
        assertEquals("STALE_STATE", g.call(McpSourcePropertiesDomain.READ, args).get("error"));
    }

    @Test
    void allTargetsAreRecheckedInsideTransactionBeforeFirstSetter() {
        var f = new Fixture();
        var request = f.request(
                "race", f.target("mesh", Map.of("visible", false)), f.target("warp", Map.of("visible", false)));
        f.before = () -> {
            var e = f.entities.get("warp");
            f.entities.put("warp", new Entity(e.nativeState, e.visible, true, e.culling, e.parent));
        };
        assertEquals("ROLLED_BACK", f.write(request).get("outcome"));
        assertEquals(0, f.writes);
    }

    @Test
    void partialSetterFailureAndUnexpectedGeometryChangesRollbackEverything() {
        for (boolean corrupt : List.of(false, true)) {
            var f = new Fixture();
            var original = Map.copyOf(f.entities);
            var request = f.request(
                    "rollback",
                    f.target("mesh", Map.of("visible", false, "culling", true)),
                    f.target("warp", Map.of("locked", true)));
            if (corrupt)
                f.afterWrite = () -> {
                    var e = f.entities.get("mesh");
                    f.entities.put(
                            "mesh",
                            new Entity(
                                    McpGeometryQualityTest.mesh(
                                            McpGeometryQualityTest.UNIT,
                                            McpGeometryQualityTest.TRI,
                                            McpGeometryQualityTest.UNIT,
                                            McpGeometryQualityTest.points(0, 0, 2, 0, 0, 1)),
                                    e.visible,
                                    e.locked,
                                    e.culling,
                                    e.parent));
                };
            else f.failAt = 2;
            assertEquals("ROLLED_BACK", f.write(request).get("outcome"));
            assertEquals(original, f.entities);
        }
    }

    @Test
    void unrequestedPropertyChangeAlsoTriggersRollback() {
        var f = new Fixture();
        var original = Map.copyOf(f.entities);
        var request = f.request("unrequested", f.target("mesh", Map.of("visible", false)));
        f.afterWrite = () -> {
            var e = f.entities.get("mesh");
            f.entities.put("mesh", new Entity(e.nativeState, e.visible, true, e.culling, e.parent));
        };
        assertEquals("ROLLED_BACK", f.write(request).get("outcome"));
        assertEquals(original, f.entities);
    }

    @Test
    void lostResponseIsUnknownAndNeverReplaysWhileReadbackWarningPreservesReceipt() {
        var f = new Fixture();
        var request = f.request("lost", f.target("mesh", Map.of("visible", false)));
        f.loseResult = true;
        var output = f.write(request);
        assertEquals("OUTCOME_UNKNOWN", output.get("outcome"));
        assertEquals(output, f.write(request));
        assertEquals(1, f.writes);
        assertFalse(f.entities.get("mesh").visible);
        var g = new Fixture();
        request = g.request("warning", g.target("mesh", Map.of("visible", false)));
        g.afterCommit = () -> g.readFailure = new IllegalStateException("private native path");
        output = g.write(request);
        assertEquals("APPLIED_WITH_READBACK_WARNING", output.get("outcome"));
        assertNotNull(output.get("receipt"));
        assertEquals(false, output.get("readbackVerified"));
        assertFalse(output.toString().contains("private native path"));
    }

    @Test
    void recoveryFailureAndUnavailableNativeServiceAreReportedWithoutFallback() {
        var f = new Fixture();
        var request = f.request("recovery", f.target("mesh", Map.of("visible", false, "locked", true)));
        f.failAt = 2;
        f.recoveryFailed = true;
        assertEquals("OUTCOME_UNKNOWN", f.write(request).get("outcome"));
        var g = new Fixture();
        request = g.request("unavailable", g.target("mesh", Map.of("visible", false)));
        g.unavailable = true;
        assertEquals("NOT_APPLIED", g.write(request).get("outcome"));
        assertEquals(0, g.writes);
    }

    @Test
    void equalValuesAreNoOpAndExplicitUnlockDoesNotChangeAnythingElse() {
        var f = new Fixture();
        var request = f.request("noop", f.target("mesh", Map.of("visible", true)));
        var output = f.write(request);
        assertEquals("NO_CHANGE", output.get("outcome"));
        assertEquals(0, f.writes);
        assertNull(((Map<?, ?>) output.get("receipt")).get("historyEntryId"));
        var g = new Fixture();
        var e = g.entities.get("mesh");
        g.entities.put("mesh", new Entity(e.nativeState, e.visible, true, e.culling, e.parent));
        output = g.write(g.request("unlock", g.target("mesh", Map.of("locked", false))));
        assertEquals("APPLIED", output.get("outcome"));
        assertEquals(1, g.writes);
        assertEquals(e, g.entities.get("mesh"));
    }

    @Test
    void sessionTtlAndPropertyTokenConfusionAreRejected() {
        var f = new Fixture();
        var target = McpRequestRegistry.inSession("one", () -> f.target("mesh", Map.of("visible", false)));
        var request = f.request("session", target);
        assertEquals(
                "NOT_FOUND",
                McpRequestRegistry.inSession("two", () -> f.write(request)).get("code"));
        assertEquals(
                "APPLIED",
                McpRequestRegistry.inSession("one", () -> f.write(request)).get("outcome"));
        assertEquals("OUTCOME_UNKNOWN", f.result("session").get("outcome"));
        var g = new Fixture();
        var expired = g.request("expired", g.target("mesh", Map.of("visible", false)));
        g.now = McpAuthoringSnapshotStore.TTL_NANOS;
        assertEquals("NOT_FOUND", g.write(expired).get("code"));
        var h = new Fixture();
        var raw = h.readArgs("mesh");
        var wrong = Map.<String, Object>of(
                "kind",
                "art_mesh",
                "objectId",
                "mesh",
                "snapshotId",
                raw.get("authoringSnapshotId"),
                "properties",
                Map.of("visible", false));
        assertEquals("STALE_STATE", h.write(h.request("wrong", wrong)).get("code"));
    }

    @Test
    void closedBooleanSchemaRejectsEmptyCurrentFormAndCrossTypeFields() {
        for (Map<String, Object> patch : List.of(
                Map.<String, Object>of(),
                Map.<String, Object>of("opacity", .5),
                Map.<String, Object>of("visible", 1),
                Map.<String, Object>of("locked", "false"))) {
            var f = new Fixture();
            var request = f.request("invalid", f.target("mesh", patch));
            assertThrows(IllegalArgumentException.class, () -> f.write(request));
            assertEquals(0, f.writes);
        }
        var g = new Fixture();
        var request = g.request("cross", g.target("warp", Map.of("culling", true)));
        assertThrows(IllegalArgumentException.class, () -> g.write(request));
        assertEquals(0, g.writes);
    }

    @Test
    void conflictingOperationAndFullResultCacheDoNotReplay() {
        var f = new Fixture();
        var target = f.target("mesh", Map.of("visible", true));
        var request = f.request("same", target);
        assertEquals("NO_CHANGE", f.write(request).get("outcome"));
        assertEquals(
                "OPERATION_ID_CONFLICT",
                f.write(f.request("same", f.target("mesh", Map.of("visible", false))))
                        .get("code"));
        for (int i = 1; i < 256; i++)
            assertEquals("NO_CHANGE", f.write(f.request("op" + i, target)).get("outcome"));
        assertEquals("RESULT_STORE_FULL", f.write(f.request("overflow", target)).get("code"));
        assertEquals("NO_CHANGE", f.result("same").get("outcome"));
        assertEquals(0, f.writes);
    }

    @Test
    void cancellationBoundariesAndDirectRunningResultDoNotMisreportHostRollback() {
        var registry = new McpRequestRegistry();
        var f = new Fixture();
        try (var scope = registry.enter("cancel-before-properties", 1)) {
            var request = f.request("cancel", f.target("mesh", Map.of("visible", false)));
            registry.cancel("cancel-before-properties", 1);
            assertThrows(java.util.concurrent.CancellationException.class, () -> f.write(request));
            assertEquals(0, f.writes);
        }
        var g = new Fixture();
        try (var scope = registry.enter("cancel-after-properties", 2)) {
            var request = g.request("after", g.target("mesh", Map.of("visible", false)));
            g.afterWrite = () -> {
                assertEquals("RUNNING", g.result("after").get("outcome"));
                registry.cancel("cancel-after-properties", 2);
            };
            assertEquals("APPLIED", g.write(request).get("outcome"));
            assertEquals(1, g.writes);
        }
    }

    @Test
    void sourceReadPermissionAndStateRacesAreRedactedAndFailClosed() {
        for (RuntimeException failure : List.of(
                new SecurityException("private"),
                new UnsupportedOperationException("private"),
                new IllegalStateException("private"))) {
            var f = new Fixture();
            var args = f.readArgs("mesh");
            f.readFailure = failure;
            var output = f.call(McpSourcePropertiesDomain.READ, args);
            assertEquals(false, output.get("ok"));
            assertFalse(output.toString().contains("private"));
        }
        var g = new Fixture();
        var args = g.readArgs("mesh");
        g.onPropertiesRead = () -> g.revision++;
        assertEquals("STALE_STATE", g.call(McpSourcePropertiesDomain.READ, args).get("error"));
    }

    private record Entity(Object nativeState, boolean visible, boolean locked, boolean culling, String parent) {}

    private static final class Fixture {
        long now, revision = 1;
        String document = "doc";
        int writes, transactions, failAt = -1;
        boolean loseResult, recoveryFailed, unavailable;
        RuntimeException readFailure;
        Runnable before = () -> {}, afterWrite = () -> {}, afterCommit = () -> {}, onPropertiesRead = () -> {};
        final Map<String, Entity> entities = new LinkedHashMap<>();
        final CubismModel model = proxy(CubismModel.class, (name, a) -> switch (name) {
            case "drawables" -> proxy(Drawables.class, (n, v) -> handle(((ArtMeshId) v[0]).value(), Drawable.class));
            case "deformers" ->
                proxy(Deformers.class, (n, v) -> {
                    String id = ((DeformerId) v[0]).value();
                    return id.equals("warp") ? handle(id, WarpDeformer.class) : handle(id, RotationDeformer.class);
                });
            default -> throw new AssertionError("Unexpected model operation " + name);
        });
        final AuthoringTransactionService service = new AuthoringTransactionService() {
            public <T> AuthoringTransactionResult<T> execute(
                    AuthoringTransactionOptions options, AuthoringTransactionWork<T> work) {
                transactions++;
                if (unavailable) return AuthoringTransactionResult.unavailable("simulated.unavailable");
                var original = Map.copyOf(entities);
                var start = history();
                T value;
                try {
                    before.run();
                    value = work.run();
                } catch (Exception failure) {
                    var r = new AuthoringTransactionReceipt("tx", options.label(), start, history(), Optional.empty());
                    if (recoveryFailed)
                        return AuthoringTransactionResult.recoveryFailed(r, "simulated.recovery-failed");
                    restore(original);
                    return AuthoringTransactionResult.rolledBack(r, "simulated.rollback");
                }
                boolean changed = !original.equals(entities);
                if (changed) revision++;
                var r = new AuthoringTransactionReceipt(
                        "tx", options.label(), start, history(), changed ? Optional.of("entry") : Optional.empty());
                afterCommit.run();
                if (loseResult) throw new IllegalStateException("lost");
                return changed
                        ? AuthoringTransactionResult.committed(value, r)
                        : AuthoringTransactionResult.noChange(value, r);
            }
        };
        final CubismFacade facade = proxy(CubismFacade.class, (name, a) -> {
            if (name.equals("authoringTransactions")) return service;
            throw new AssertionError(name);
        });
        final McpAuthoringSnapshotStore store = new McpAuthoringSnapshotStore(() -> now);
        final McpExecutionBridge execution = new McpExecutionBridge(new UiScheduler() {
            public Registration runOnUiThread(Runnable r) {
                r.run();
                return () -> {};
            }

            public Registration runOnUiThreadLater(Runnable r, Duration d) {
                throw new AssertionError();
            }
        });
        final McpToolCatalog tools = new McpSourcePropertiesDomain(
                        facade, execution, store, () -> new McpStoredAuthoringDomain.Context(model, binding()))
                .tools();

        Fixture() {
            entities.put(
                    "mesh",
                    new Entity(
                            McpGeometryQualityTest.mesh(
                                    McpGeometryQualityTest.UNIT,
                                    McpGeometryQualityTest.TRI,
                                    McpGeometryQualityTest.UNIT,
                                    McpGeometryQualityTest.UNIT),
                            true,
                            false,
                            false,
                            null));
            entities.put(
                    "warp",
                    new Entity(
                            new WarpAuthoringSnapshot(
                                    new DeformerId("warp"),
                                    "source-warp",
                                    1,
                                    1,
                                    false,
                                    List.of(),
                                    List.of(),
                                    List.of()),
                            true,
                            false,
                            false,
                            null));
            entities.put(
                    "rotation",
                    new Entity(
                            new RotationAuthoringSnapshot(
                                    new DeformerId("rotation"), "source-rotation", 0, List.of(), List.of(), List.of()),
                            true,
                            false,
                            false,
                            null));
        }

        <T> T handle(String id, Class<T> type) {
            if (!entities.containsKey(id)) throw new NoSuchElementException();
            return proxy(type, (name, a) -> {
                if (readFailure != null && !name.startsWith("set")) throw readFailure;
                var e = entities.get(id);
                return switch (name) {
                    case "authoringSnapshot" -> e.nativeState;
                    case "parentPartId" -> Optional.empty();
                    case "parentDeformerId" -> Optional.ofNullable(e.parent).map(DeformerId::new);
                    case "visible" -> {
                        onPropertiesRead.run();
                        yield e.visible;
                    }
                    case "locked" -> e.locked;
                    case "culling" -> e.culling;
                    case "setVisible", "setLocked", "setCulling" -> {
                        writes++;
                        if (writes == failAt) throw new UnsupportedOperationException("native unavailable");
                        boolean flag = (Boolean) a[0];
                        entities.put(
                                id,
                                new Entity(
                                        e.nativeState,
                                        name.equals("setVisible") ? flag : e.visible,
                                        name.equals("setLocked") ? flag : e.locked,
                                        name.equals("setCulling") ? flag : e.culling,
                                        e.parent));
                        afterWrite.run();
                        yield null;
                    }
                    default ->
                        throw new AssertionError("Unexpected current-form/effective/selection operation " + name);
                };
            });
        }

        void restore(Map<String, Entity> state) {
            entities.clear();
            entities.putAll(state);
        }

        Map<String, Object> binding() {
            return Map.of(
                    "documentId",
                    document,
                    "modelId",
                    "model",
                    "historyGeneration",
                    1L,
                    "historyRevision",
                    revision,
                    "historyDocumentBinding",
                    "hd",
                    "historyManagerBinding",
                    "hm");
        }

        HistorySnapshot history() {
            return new HistorySnapshot(
                    HistorySnapshot.Availability.AVAILABLE, 1, revision, 0, List.of(), false, false, "hd", "hm");
        }

        String kind(String id) {
            return id.equals("mesh") ? "art_mesh" : id.equals("warp") ? "warp_deformer" : "rotation_deformer";
        }

        Map<String, Object> readArgs(String id) {
            var e = entities.get(id);
            var parent = new LinkedHashMap<String, Object>();
            parent.put("kind", "parent_space");
            parent.put("parentPartId", null);
            parent.put("parentDeformerId", e.parent);
            String guid = id.equals("mesh") ? "source" : "source-" + id;
            var token = store.put(
                    McpRequestRegistry.currentSessionId(),
                    binding(),
                    kind(id),
                    id,
                    guid,
                    e.nativeState,
                    List.of(parent),
                    1000);
            return new LinkedHashMap<>(Map.of(
                    "documentId",
                    "doc",
                    "modelId",
                    "model",
                    "kind",
                    kind(id),
                    "objectId",
                    id,
                    "authoringSnapshotId",
                    token.snapshotId()));
        }

        Map<String, Object> read(String id) {
            return call(McpSourcePropertiesDomain.READ, readArgs(id));
        }

        Map<String, Object> target(String id, Map<String, Object> patch) {
            var read = read(id);
            assertEquals(true, read.get("ok"), read.toString());
            return Map.of("kind", kind(id), "objectId", id, "snapshotId", read.get("snapshotId"), "properties", patch);
        }

        @SafeVarargs
        final Map<String, Object> request(String operation, Map<String, Object>... targets) {
            return Map.of(
                    "documentId", "doc", "modelId", "model", "operationId", operation, "targets", List.of(targets));
        }

        Map<String, Object> write(Map<String, Object> args) {
            return call(McpSourcePropertiesDomain.WRITE, args);
        }

        Map<String, Object> result(String id) {
            return call(McpSourcePropertiesDomain.RESULT, Map.of("operationId", id));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> call(String name, Map<String, Object> args) {
            return (Map<String, Object>) tools.call(name, args).get("structuredContent");
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> fn) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> fn.apply(m.getName(), a));
    }
}
