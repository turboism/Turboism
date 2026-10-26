package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.*;
import dev.turboism.sdk.cubism.history.*;
import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.transaction.*;
import dev.turboism.sdk.json.Json;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** MCP lifecycle/CAS tests use detached explicit grids, not evidence of native grid generation. */
class McpBindingInitializationDomainTest {
    @ParameterizedTest
    @ValueSource(strings = {"art_mesh", "warp_deformer", "rotation_deformer"})
    void completeOneTwoAndThreeAxisGridsKeepDefaultContentAndRetainCanonicalTuples(String kind) {
        for (int dimensions = 1; dimensions <= 3; dimensions++) {
            var f = new Fixture(kind);
            var original = f.snapshot;
            var args = f.request("initialize", f.plan(dimensions));
            assertEquals(0, f.writes);
            assertEquals(0, f.transactions);
            var output = f.apply(args);
            assertEquals("APPLIED", output.get("outcome"), output.toString());
            assertEquals(true, output.get("readbackVerified"));
            assertEquals(false, output.get("renderVerified"));
            assertNotNull(output.get("receipt"));
            assertEquals(dimensions, f.writes);
            assertEquals(1, f.transactions);
            assertEquals(Math.pow(3, dimensions), ((List<?>) output.get("cells")).size());
            var cells = ((List<?>) output.get("cells"))
                    .stream().map(McpBindingInitializationDomainTest::map).toList();
            assertEquals(
                    1,
                    cells.stream()
                            .filter(c -> Boolean.TRUE.equals(c.get("defaultCell")))
                            .count());
            assertEquals(
                    cells.size(),
                    cells.stream().map(c -> c.get("formGuid")).distinct().count());
            assertTrue(cells.stream().allMatch(c -> c.get("formGuid").toString().startsWith("native-")));
            assertEquals(
                    McpBindingGridSnapshot.initial(original),
                    McpBindingGridSnapshot.view(f.snapshot)
                            .forms()
                            .values()
                            .iterator()
                            .next());
            assertNotEquals(output.get("beforeHash"), output.get("expectedAfterHash"));
            assertThrows(
                    NoSuchElementException.class, () -> f.store.get(f.token, McpRequestRegistry.currentSessionId()));
            f.forbidHost = true;
            assertEquals(output, f.apply(args));
            assertEquals(output, f.result("initialize"));
            var conflict = new LinkedHashMap<>(args);
            conflict.put("planId", "another");
            assertEquals("OPERATION_ID_CONFLICT", f.apply(conflict).get("code"));
        }
    }

    @Test
    void permissionTargetMustEchoTheActualPlanAndSurvivesJsonRoundTrip() {
        var f = new Fixture("art_mesh");
        var planArgs = f.planArgs(1);
        planArgs.put(
                "axes",
                List.of(Map.of(
                        "parameterId", "x", "parameterGuid", f.params.get(0).guid, "keys", List.of(-.3, 0, .3))));
        var plan = f.call(McpBindingInitializationDomain.PLAN, planArgs);
        assertEquals(true, plan.get("ok"), plan.toString());
        var args = map(Json.parseObject(Json.stringify(f.request("wire", plan))));
        var changed = new LinkedHashMap<>(args);
        var target = new LinkedHashMap<>(map(changed.get("target")));
        target.put("objectId", "wrong");
        changed.put("target", target);
        assertEquals("STALE_STATE", f.apply(changed).get("code"));
        assertEquals(0, f.writes);
        assertEquals("APPLIED", f.apply(args).get("outcome"));
    }

    @Test
    void invalidAxesAndUnsupportedParametersNeverStartTransactions() {
        for (String fault : List.of(
                "missingDefault",
                "noLower",
                "outside",
                "near",
                "duplicate",
                "nondefault",
                "repeat",
                "combined",
                "pair",
                "morph",
                "guid",
                "duplicateId",
                "duplicateGuid")) {
            var f = new Fixture("art_mesh");
            var axes = new ArrayList<>(f.axisRows(1));
            var axis = new LinkedHashMap<>(axes.get(0));
            switch (fault) {
                case "missingDefault" -> axis.put("keys", List.of(-1, -.5, 1));
                case "noLower" -> axis.put("keys", List.of(0, .5, 1));
                case "outside" -> axis.put("keys", List.of(-2, 0, 1));
                case "near" -> axis.put("keys", List.of(-1, 0, .0000001, 1));
                case "duplicate" -> axes.add(axis);
                case "nondefault" -> f.params.get(0).value = .5f;
                case "repeat" -> f.params.get(0).repeat = true;
                case "combined" -> f.params.get(0).combined = true;
                case "pair" -> f.params.get(0).partner = "y";
                case "morph" -> f.params.get(0).type = ParameterType.BLEND_SHAPE;
                case "guid" -> axis.put("parameterGuid", "not-the-source");
                case "duplicateId" -> f.params.get(1).id = "x";
                case "duplicateGuid" -> f.params.get(1).guid = f.params.get(0).guid;
            }
            axes.set(0, axis);
            var args = f.planArgs(1);
            args.put("axes", axes);
            assertEquals(
                    false, f.call(McpBindingInitializationDomain.PLAN, args).get("ok"), fault);
            assertEquals(0, f.writes);
            assertEquals(0, f.transactions);
        }
    }

    @Test
    void fullStateCasRejectsChangesEvenWithUnchangedHistory() {
        for (String fault : List.of(
                "source",
                "form",
                "parent",
                "part",
                "lock",
                "defaultLock",
                "level",
                "guid",
                "value",
                "name",
                "range",
                "repeat",
                "order",
                "revision",
                "generation",
                "document",
                "manager")) {
            var f = new Fixture("rotation_deformer");
            var args = f.request("stale", f.plan(2));
            switch (fault) {
                case "source" -> f.source = "recreated";
                case "form" -> f.rotation = new RotationDeformerForm(12, 0, 0, 1, false, false);
                case "parent" -> f.parent = "new-parent";
                case "part" -> f.part = "new-part";
                case "lock" -> f.locked = true;
                case "defaultLock" -> f.defaultLocked = true;
                case "level" -> f.level = ModelEditLevel.LEVEL_1;
                case "guid" -> f.params.get(0).guid = "new-param-source";
                case "value" -> f.params.get(2).value = .5f;
                case "name" -> f.params.get(0).name = "renamed";
                case "range" -> f.params.get(0).maximum = 2;
                case "repeat" -> f.params.get(0).repeat = true;
                case "order" -> Collections.reverse(f.params);
                case "revision" -> f.revision++;
                case "generation" -> f.generation++;
                case "document" -> f.document = "other";
                case "manager" -> f.manager = "other";
            }
            if (fault.equals("source") || fault.equals("form")) f.rebuild();
            assertEquals("STALE_STATE", f.apply(args).get("code"), fault);
            assertEquals(0, f.transactions);
            assertEquals(0, f.writes);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"art_mesh", "warp_deformer", "rotation_deformer"})
    void incompleteSharedDuplicateAndChangedGridsRollbackBeforeNextAxis(String kind) {
        for (String fault :
                List.of("missing", "shared", "duplicateTuple", "content", "source", "axis", "pose", "throw")) {
            var f = new Fixture(kind);
            var original = f.snapshot;
            f.fault = fault;
            var output = f.apply(f.request("bad-grid", f.plan(2)));
            assertEquals("ROLLED_BACK", output.get("outcome"), kind + ":" + fault + output);
            assertEquals(original, f.snapshot);
            assertEquals(true, output.get("rollbackStateVerified"));
            assertEquals(List.of(), output.get("cells"));
            assertEquals(false, output.get("readbackVerified"));
            assertEquals(1, f.writes, "reject after the first bad axis");
            assertEquals(0f, f.params.get(0).value);
        }
    }

    @Test
    void transactionPreflightRaceRollbackFailureAndResponseLossAreDistinct() {
        var f = new Fixture("art_mesh");
        var args = f.request("race", f.plan(1));
        f.before = () -> f.params.get(0).guid = "replaced-between-checks";
        assertEquals(
                "OUTCOME_UNKNOWN", f.apply(args).get("outcome")); // fixture cannot restore recreated parameter identity
        assertEquals(0, f.writes);
        for (String fault : List.of("unavailable", "recovery", "lost")) {
            var g = new Fixture("warp_deformer");
            g.unavailable = fault.equals("unavailable");
            g.recoveryFailed = fault.equals("recovery");
            g.loseResult = fault.equals("lost");
            if (g.recoveryFailed) g.fault = "throw";
            var request = g.request(fault, g.plan(1));
            var output = g.apply(request);
            assertEquals(fault.equals("unavailable") ? "NOT_APPLIED" : "OUTCOME_UNKNOWN", output.get("outcome"));
            g.forbidHost = true;
            assertEquals(output, g.apply(request));
        }
    }

    @Test
    void postCommitReadFailureKeepsWarningAndDoesNotReturnUnverifiedCells() {
        var f = new Fixture("rotation_deformer");
        var args = f.request("warning", f.plan(2));
        f.afterCommit = () -> f.params.get(2).value = .5f;
        var result = f.apply(args);
        assertEquals("APPLIED_WITH_READBACK_WARNING", result.get("outcome"));
        assertEquals(false, result.get("readbackVerified"));
        assertEquals(List.of(), result.get("cells"));
        f.forbidHost = true;
        assertEquals(result, f.result("warning"));
    }

    @Test
    void sourceAndPlanTtlCrossSessionAndMissingResultsAreClosed() {
        var f = new Fixture("art_mesh");
        var args = McpRequestRegistry.inSession("a", () -> f.request("ttl", f.plan(1)));
        assertEquals(
                "NOT_FOUND",
                McpRequestRegistry.inSession("b", () -> f.apply(args)).get("code"));
        f.now = McpAuthoringSnapshotStore.TTL_NANOS;
        assertEquals(
                "NOT_FOUND",
                McpRequestRegistry.inSession("a", () -> f.apply(args)).get("code"));
        assertEquals(0, f.writes);
        f.forbidHost = true;
        assertEquals("OUTCOME_UNKNOWN", f.result("never-executed").get("outcome"));
        var g = new Fixture("art_mesh");
        var planArgs = g.planArgs(1);
        g.now = McpAuthoringSnapshotStore.TTL_NANOS - 1;
        var nearExpiry = g.call(McpBindingInitializationDomain.PLAN, planArgs);
        assertEquals(true, nearExpiry.get("ok"));
        // Plan issuance may not renew an older source snapshot.
        g.now = McpAuthoringSnapshotStore.TTL_NANOS;
        assertEquals(
                "NOT_FOUND", g.apply(g.request("expired-source", nearExpiry)).get("code"));
    }

    @Test
    void lockedBoundMorphAndOversizedGridsAreNotInitialized() {
        var f = new Fixture("art_mesh");
        f.locked = true;
        assertEquals(
                "UNAVAILABLE",
                f.call(McpBindingInitializationDomain.PLAN, f.planArgs(1)).get("error"));
        var g = new Fixture("art_mesh");
        g.bound.add(g.axis(0, List.of(-1f, 0f, 1f)));
        g.rebuild();
        assertEquals(
                "UNAVAILABLE",
                g.call(McpBindingInitializationDomain.PLAN, g.planArgs(1)).get("error"));
        var h = new Fixture("art_mesh");
        var args = h.planArgs(3);
        var axes = new ArrayList<>(h.axisRows(3));
        var axis = new LinkedHashMap<>(axes.get(0));
        axis.put("keys", List.of(-1, -.5, 0, .5, 1));
        axes.set(0, axis);
        args.put("axes", axes);
        assertEquals(
                "BUDGET_EXCEEDED",
                h.call(McpBindingInitializationDomain.PLAN, args).get("error"));
        assertEquals(0, h.writes);
    }

    @Test
    void morphAndAmbiguousZeroAxisFormsCannotBeUsedAsInitialState() {
        for (String fault : List.of("morph", "shared", "empty")) {
            var f = new Fixture("art_mesh");
            var original = (ArtMeshAuthoringSnapshot) f.snapshot;
            var cells = fault.equals("empty")
                    ? List.<ArtMeshAuthoringSnapshot.Cell>of()
                    : fault.equals("shared")
                            ? List.of(original.cells().get(0), original.cells().get(0))
                            : original.cells();
            f.snapshot = new ArtMeshAuthoringSnapshot(
                    original.artMeshId(),
                    original.sourceGuid(),
                    original.sourceGeometry(),
                    original.axes(),
                    cells,
                    original.forms(),
                    fault.equals("morph")
                            ? List.of(new ArtMeshAuthoringSnapshot.MorphKey(
                                    "morph-parameter", original.forms().get(0).formGuid(), Optional.of(.5f)))
                            : List.of());
            assertEquals(
                    "UNAVAILABLE",
                    f.call(McpBindingInitializationDomain.PLAN, f.planArgs(1)).get("error"));
            assertEquals(0, f.writes);
        }
    }

    @Test
    void pointSampleAndParameterBudgetsRejectBeforeTransactionAdmission() {
        for (int size : List.of(2048, 4097)) {
            var f = new Fixture("art_mesh");
            var points = new ArrayList<>(TRIANGLE);
            while (points.size() < size) points.add(new Point2(0, 0));
            f.snapshot = new ArtMeshAuthoringSnapshot(
                    new ArtMeshId("object"),
                    f.source,
                    new ArtMeshGeometry(points, points, List.of(0, 1, 2)),
                    List.of(),
                    List.of(new ArtMeshAuthoringSnapshot.Cell("initial", List.of())),
                    List.of(new ArtMeshAuthoringSnapshot.Form("initial", points, 17, Optional.of(.75f))),
                    List.of());
            assertEquals(
                    "BUDGET_EXCEEDED",
                    f.call(McpBindingInitializationDomain.PLAN, f.planArgs(3)).get("error"));
            assertEquals(0, f.transactions);
        }
        var g = new Fixture("rotation_deformer");
        while (g.params.size() < 513) g.params.add(new P("extra-" + g.params.size()));
        assertEquals(
                "BUDGET_EXCEEDED",
                g.call(McpBindingInitializationDomain.PLAN, g.planArgs(1)).get("error"));
        assertEquals(0, g.transactions);
    }

    @Test
    void cancellationBeforeAdmissionWritesNothingAndAfterAdmissionRetainsCommittedResult() {
        var registry = new McpRequestRegistry();
        var f = new Fixture("art_mesh");
        try (var ignored = registry.enter("before", 1)) {
            var args = f.request("cancel", f.plan(1));
            registry.cancel("before", 1);
            assertThrows(java.util.concurrent.CancellationException.class, () -> f.apply(args));
            assertEquals(0, f.writes);
        }
        var g = new Fixture("art_mesh");
        try (var ignored = registry.enter("after", 2)) {
            var args = g.request("committed", g.plan(2));
            g.onBind = () -> {
                g.onBind = () -> {};
                assertEquals("RUNNING", g.result("committed").get("outcome"));
                registry.cancel("after", 2);
            };
            assertEquals("APPLIED", g.apply(args).get("outcome"));
            assertEquals(2, g.writes);
        }
    }

    @Test
    void resultCapacityNeverEvictsAndPlanEvictionCannotAuthorizeWrites() {
        var f = new Fixture("rotation_deformer");
        var old = f.request("evicted", f.plan(1));
        for (int i = 0; i < 16; i++) f.plan(1);
        assertEquals("NOT_FOUND", f.apply(old).get("code"));
        f.unavailable = true;
        for (int i = 0; i < 128; i++)
            assertEquals("NOT_APPLIED", f.apply(f.request("op" + i, f.plan(1))).get("outcome"));
        assertEquals("RESULT_STORE_FULL", f.apply(f.request("full", f.plan(1))).get("code"));
        f.forbidHost = true;
        assertEquals("NOT_APPLIED", f.result("op0").get("outcome"));
    }

    static final class P {
        String id, guid, name, partner;
        float value, maximum = 1;
        boolean repeat, combined;
        ParameterType type = ParameterType.NORMAL;

        P(String id) {
            this.id = id;
            guid = "parameter-native-" + id;
            name = id;
        }

        Parameter handle() {
            return proxy(Parameter.class, (n, a) -> switch (n) {
                case "id" -> new ParameterId(id);
                case "guid" -> guid;
                case "name" -> Optional.of(name);
                case "getMinimumValue" -> -1f;
                case "getDefaultValue" -> 0f;
                case "getMaximumValue" -> maximum;
                case "getValue" -> value;
                case "type" -> type;
                case "repeat" -> Optional.of(repeat);
                case "combined" -> Optional.of(combined);
                case "combinedWith" -> Optional.ofNullable(partner).map(ParameterId::new);
                default -> throw new AssertionError(n);
            });
        }
    }

    static final class Fixture {
        String kind, source = "source-native", parent, part, document = "doc", manager = "manager", fault = "";
        long now, generation = 1, revision;
        int writes, transactions;
        boolean locked, defaultLocked, forbidHost, unavailable, recoveryFailed, loseResult;
        ModelEditLevel level = ModelEditLevel.LEVEL_3;
        RotationDeformerForm rotation = new RotationDeformerForm(0, 2, 3, 1, true, false);
        List<P> params = new ArrayList<>(List.of(new P("x"), new P("y"), new P("z")));
        List<McpBindingGridSnapshot.Axis> bound = new ArrayList<>();
        Object snapshot;
        String token;
        Runnable before = () -> {}, afterCommit = () -> {}, onBind = () -> {};
        final McpAuthoringSnapshotStore store = new McpAuthoringSnapshotStore(() -> now);
        final McpToolCatalog tools;

        Fixture(String kind) {
            this.kind = kind;
            rebuild();
            var model = proxy(CubismModel.class, (n, a) -> {
                checkHost();
                return switch (n) {
                    case "drawables" -> proxy(Drawables.class, (n0, a0) -> object(Drawable.class));
                    case "deformers" ->
                        proxy(
                                Deformers.class,
                                (n0, a0) -> kind.equals("warp_deformer")
                                        ? object(WarpDeformer.class)
                                        : object(RotationDeformer.class));
                    case "parameters" ->
                        proxy(Parameters.class, (n0, a0) -> {
                            assertEquals("all", n0);
                            return params.stream().map(P::handle).toList();
                        });
                    case "parameterBindings" ->
                        proxy(ParameterBindingOperations.class, (n0, a0) -> {
                            assertEquals("bindValues", n0);
                            assertEquals("object", ((ParameterBindingTarget) a0[0]).id());
                            writes++;
                            var p = params.stream()
                                    .filter(v -> v.id.equals(((ParameterId) a[0]).value()))
                                    .findFirst()
                                    .orElseThrow();
                            @SuppressWarnings("unchecked")
                            var keys = (List<Float>) a0[1];
                            var axis = new McpBindingGridSnapshot.Axis(p.guid, p.id, -1, 0, p.maximum, keys);
                            if (fault.equals("axis"))
                                axis = new McpBindingGridSnapshot.Axis(p.guid, p.id, -1, 0, 2, keys);
                            bound.add(axis);
                            if (fault.equals("source")) source = "changed";
                            if (fault.equals("pose")) params.get(0).value = .5f;
                            rebuild();
                            onBind.run();
                            if (fault.equals("throw")) throw new IllegalStateException("private partial failure");
                            return null;
                        });
                    case "defaultKeyformLocked" -> defaultLocked;
                    case "editLevel" -> level;
                    default -> throw new AssertionError(n);
                };
            });
            AuthoringTransactionService service = new AuthoringTransactionService() {
                public <T> AuthoringTransactionResult<T> execute(
                        AuthoringTransactionOptions options, AuthoringTransactionWork<T> work) {
                    checkHost();
                    transactions++;
                    if (unavailable) return AuthoringTransactionResult.unavailable("test.unavailable");
                    var original = snapshot;
                    var axesBefore = new ArrayList<>(bound);
                    var sourceBefore = source;
                    var values = params.stream().map(p -> p.value).toList();
                    var history = history();
                    T value;
                    try {
                        before.run();
                        value = work.run();
                    } catch (Exception failure) {
                        var receipt = new AuthoringTransactionReceipt(
                                "tx", options.label(), history, history(), Optional.empty());
                        if (recoveryFailed) return AuthoringTransactionResult.recoveryFailed(receipt, "test.recovery");
                        snapshot = original;
                        bound = axesBefore;
                        source = sourceBefore;
                        for (int i = 0; i < params.size(); i++) params.get(i).value = values.get(i);
                        return AuthoringTransactionResult.rolledBack(receipt, "test.rollback");
                    }
                    revision++;
                    var receipt = new AuthoringTransactionReceipt(
                            "tx", options.label(), history, history(), Optional.of("entry"));
                    afterCommit.run();
                    if (loseResult) throw new IllegalStateException("private response loss");
                    return AuthoringTransactionResult.committed(value, receipt);
                }
            };
            var facade = proxy(CubismFacade.class, (n, a) -> {
                checkHost();
                assertEquals("authoringTransactions", n);
                return service;
            });
            var execution = new McpExecutionBridge(new UiScheduler() {
                public Registration runOnUiThread(Runnable task) {
                    task.run();
                    return () -> {};
                }

                public Registration runOnUiThreadLater(Runnable task, Duration delay) {
                    throw new AssertionError();
                }
            });
            tools = new McpBindingInitializationDomain(
                            facade,
                            execution,
                            store,
                            () -> {
                                checkHost();
                                return new McpStoredAuthoringDomain.Context(model, binding());
                            },
                            () -> now)
                    .tools();
        }

        <T> T object(Class<T> type) {
            return proxy(type, (n, a) -> {
                checkHost();
                return switch (n) {
                    case "authoringSnapshot" -> snapshot;
                    case "parentPartId" -> Optional.ofNullable(part).map(PartId::new);
                    case "parentDeformerId" -> Optional.ofNullable(parent).map(DeformerId::new);
                    case "locked" -> locked;
                    default -> throw new AssertionError(n);
                };
            });
        }

        void checkHost() {
            if (forbidHost) throw new AssertionError("No host read permitted");
        }

        Map<String, Object> binding() {
            return Map.of(
                    "documentId",
                    document,
                    "modelId",
                    "model",
                    "historyGeneration",
                    generation,
                    "historyRevision",
                    revision,
                    "historyDocumentBinding",
                    "binding",
                    "historyManagerBinding",
                    manager);
        }

        HistorySnapshot history() {
            return new HistorySnapshot(
                    HistorySnapshot.Availability.AVAILABLE,
                    generation,
                    revision,
                    0,
                    List.of(),
                    false,
                    false,
                    "binding",
                    manager);
        }

        McpBindingGridSnapshot.Axis axis(int index, List<Float> keys) {
            var p = params.get(index);
            return new McpBindingGridSnapshot.Axis(p.guid, p.id, -1, 0, 1, keys);
        }

        List<Map<String, Object>> axisRows(int dimensions) {
            return java.util.stream.IntStream.range(0, dimensions)
                    .mapToObj(i -> Map.<String, Object>of(
                            "parameterId",
                            params.get(i).id,
                            "parameterGuid",
                            params.get(i).guid,
                            "keys",
                            List.of(1, 0, -1)))
                    .toList();
        }

        Map<String, Object> planArgs(int dimensions) {
            var row = new LinkedHashMap<String, Object>();
            row.put("kind", "parent_space");
            row.put("parentPartId", part);
            row.put("parentDeformerId", parent);
            token = store.put(
                            McpRequestRegistry.currentSessionId(),
                            binding(),
                            kind,
                            "object",
                            source,
                            snapshot,
                            List.of(row),
                            1024)
                    .snapshotId();
            return new LinkedHashMap<>(Map.of(
                    "documentId",
                    document,
                    "modelId",
                    "model",
                    "kind",
                    kind,
                    "objectId",
                    "object",
                    "authoringSnapshotId",
                    token,
                    "axes",
                    axisRows(dimensions)));
        }

        Map<String, Object> plan(int dimensions) {
            var output = call(McpBindingInitializationDomain.PLAN, planArgs(dimensions));
            assertEquals(true, output.get("ok"), output.toString());
            return output;
        }

        Map<String, Object> request(String id, Map<String, Object> plan) {
            return Map.of(
                    "documentId",
                    document,
                    "modelId",
                    "model",
                    "planId",
                    plan.get("planId"),
                    "operationId",
                    id,
                    "target",
                    plan.get("target"));
        }

        Map<String, Object> apply(Map<String, Object> args) {
            return call(McpBindingInitializationDomain.APPLY, args);
        }

        Map<String, Object> result(String id) {
            return call(McpBindingInitializationDomain.RESULT, Map.of("operationId", id));
        }

        Map<String, Object> call(String name, Map<String, Object> args) {
            return map(tools.call(name, args).get("structuredContent"));
        }

        void rebuild() {
            List<Map<String, Float>> tuples = new ArrayList<>(List.of(Map.of()));
            for (var axis : bound) {
                var next = new ArrayList<Map<String, Float>>();
                for (var tuple : tuples)
                    for (float key : axis.keys()) {
                        var row = new LinkedHashMap<>(tuple);
                        row.put(axis.guid(), key);
                        next.add(Map.copyOf(row));
                    }
                tuples = next;
            }
            if (!bound.isEmpty() && fault.equals("missing")) tuples.remove(tuples.size() - 1);
            if (!bound.isEmpty() && fault.equals("duplicateTuple")) tuples.set(tuples.size() - 1, tuples.get(0));
            var meshForms = new ArrayList<ArtMeshAuthoringSnapshot.Form>();
            var meshCells = new ArrayList<ArtMeshAuthoringSnapshot.Cell>();
            var warpForms = new ArrayList<WarpAuthoringSnapshot.Form>();
            var warpCells = new ArrayList<WarpAuthoringSnapshot.Cell>();
            var rotationForms = new ArrayList<RotationAuthoringSnapshot.Form>();
            var rotationCells = new ArrayList<RotationAuthoringSnapshot.Cell>();
            for (int i = 0; i < tuples.size(); i++) {
                String guid = "native-"
                        + (bound.isEmpty() ? "original" : bound.size() + "-" + (fault.equals("shared") ? 0 : i));
                var tuple = tuples.get(i);
                meshCells.add(new ArtMeshAuthoringSnapshot.Cell(
                        guid,
                        tuple.entrySet().stream()
                                .map(e -> new ArtMeshAuthoringSnapshot.Coordinate(e.getKey(), e.getValue()))
                                .toList()));
                warpCells.add(new WarpAuthoringSnapshot.Cell(
                        guid,
                        tuple.entrySet().stream()
                                .map(e -> new WarpAuthoringSnapshot.Coordinate(e.getKey(), e.getValue()))
                                .toList()));
                rotationCells.add(new RotationAuthoringSnapshot.Cell(
                        guid,
                        tuple.entrySet().stream()
                                .map(e -> new RotationAuthoringSnapshot.Coordinate(e.getKey(), e.getValue()))
                                .toList()));
                if (i > 0 && fault.equals("shared")) continue;
                boolean corrupt = !bound.isEmpty() && fault.equals("content") && i == 0;
                meshForms.add(new ArtMeshAuthoringSnapshot.Form(
                        guid,
                        corrupt ? List.of(new Point2(1, 1), new Point2(1, 0), new Point2(0, 1)) : TRIANGLE,
                        17,
                        Optional.of(.75f)));
                warpForms.add(new WarpAuthoringSnapshot.Form(
                        guid,
                        corrupt
                                ? List.of(new Point2(1, 1), new Point2(1, 0), new Point2(0, 1), new Point2(1, 1))
                                : QUAD));
                rotationForms.add(new RotationAuthoringSnapshot.Form(
                        guid, corrupt ? new RotationDeformerForm(12, 0, 0, 1, false, false) : rotation));
            }
            snapshot = switch (kind) {
                case "art_mesh" ->
                    new ArtMeshAuthoringSnapshot(
                            new ArtMeshId("object"),
                            source,
                            new ArtMeshGeometry(TRIANGLE, TRIANGLE, List.of(0, 1, 2)),
                            bound.stream()
                                    .map(a -> new ArtMeshAuthoringSnapshot.Axis(
                                            a.guid(),
                                            new ParameterId(a.id()),
                                            a.minimum(),
                                            a.defaultValue(),
                                            a.maximum(),
                                            a.keys()))
                                    .toList(),
                            meshCells,
                            meshForms,
                            List.of());
                case "warp_deformer" ->
                    new WarpAuthoringSnapshot(
                            new DeformerId("object"),
                            source,
                            1,
                            1,
                            true,
                            bound.stream()
                                    .map(a -> new WarpAuthoringSnapshot.Axis(
                                            a.guid(),
                                            new ParameterId(a.id()),
                                            a.minimum(),
                                            a.defaultValue(),
                                            a.maximum(),
                                            a.keys()))
                                    .toList(),
                            warpCells,
                            warpForms);
                default ->
                    new RotationAuthoringSnapshot(
                            new DeformerId("object"),
                            source,
                            5,
                            bound.stream()
                                    .map(a -> new RotationAuthoringSnapshot.Axis(
                                            a.guid(),
                                            new ParameterId(a.id()),
                                            a.minimum(),
                                            a.defaultValue(),
                                            a.maximum(),
                                            a.keys()))
                                    .toList(),
                            rotationCells,
                            rotationForms);
            };
        }
    }

    static final List<Point2> TRIANGLE = List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1));
    static final List<Point2> QUAD = List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1), new Point2(1, 1));

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> body) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (o, m, a) -> body.apply(m.getName(), a));
    }
}
