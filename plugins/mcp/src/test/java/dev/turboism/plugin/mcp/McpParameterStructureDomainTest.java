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

class McpParameterStructureDomainTest {
    static Map<String, Object> group(String id) {
        return Map.of("id", id);
    }

    static Map<String, Object> create(String ref, String name) {
        return Map.of("kind", "create_group", "ref", ref, "name", name);
    }

    static Map<String, Object> rename(Object group, String name) {
        return Map.of("kind", "rename_group", "group", group, "name", name);
    }

    static Map<String, Object> move(String p, Object group, int index) {
        return Map.of("kind", "move_parameter", "parameterId", p, "targetGroup", group, "insertIndex", index);
    }

    static Map<String, Object> combine(String a, String b) {
        return Map.of("kind", "combine", "parameterId", a, "partnerId", b);
    }

    static Map<String, Object> uncombine(String a) {
        return Map.of("kind", "uncombine", "parameterId", a);
    }

    static Map<String, Object> remove(String id, List<String> groups, List<String> params) {
        return Map.of(
                "kind", "remove_group", "group", group(id), "expectedGroupIds", groups, "expectedParameterIds", params);
    }

    @Test
    void discoversActualMixedOrderAndNeverReadsValuesOrBindings() {
        var f = new Fixture();
        var read = f.read();
        assertEquals(true, read.get("ok"), read.toString());
        assertEquals("PARAMETER_GUIDS_GROUP_IDS_AND_HISTORY_BINDING", read.get("identityScope"));
        var root = (Map<?, ?>) ((List<?>) ((Map<?, ?>) read.get("structure")).get("groups")).get(0);
        assertEquals(
                List.of(
                        Map.of("kind", "PARAMETER", "id", "a"),
                        Map.of("kind", "GROUP", "id", "folder"),
                        Map.of("kind", "PARAMETER", "id", "c")),
                root.get("children"));
        assertEquals(0, f.writes);
        assertEquals(0, f.transactions);
        for (String name : List.of(
                McpParameterStructureDomain.READ,
                McpParameterStructureDomain.PLAN,
                McpParameterStructureDomain.APPLY,
                McpParameterStructureDomain.RESULT)) {
            assertTrue(f.tools.registration(name).exactOutputSchema());
            assertFalse(f.tools.registration(name).transactionEligible());
        }
        assertEquals(
                McpExecutionAffinity.DIRECT,
                f.tools.registration(McpParameterStructureDomain.RESULT).affinity());
    }

    @Test
    void createReferenceMoveRenameAndPairingCommitOnceWithObservedNativeIds() {
        var f = new Fixture();
        var plan = f.plan(
                create("eyes", "Eyes"),
                move("a", Map.of("createdRef", "eyes"), 0),
                move("b", Map.of("createdRef", "eyes"), 0),
                rename(Map.of("createdRef", "eyes"), "Head"),
                combine("a", "b"));
        assertEquals(true, plan.get("ok"), plan.toString());
        assertEquals(0, f.writes);
        assertEquals(0, f.transactions);
        assertEquals("NOT_CHECKED", plan.get("writeAdmission"));
        var output = f.apply("rig", plan);
        assertEquals("APPLIED", output.get("outcome"), output.toString());
        assertEquals(true, output.get("readbackVerified"));
        assertNotEquals(output.get("expectedAfterHash"), output.get("actualAfterHash"));
        assertEquals(List.of(Map.of("ref", "eyes", "groupId", "native-1")), output.get("createdGroups"));
        assertEquals(List.of("p:a", "p:b"), f.groups.get("native-1").children);
        assertEquals("Head", f.groups.get("native-1").name);
        assertEquals("b", f.params.get("a").partner);
        assertEquals(1, f.transactions);
        assertEquals(5, f.writes);
        int reads = f.reads;
        f.noReads = true;
        assertEquals(output, f.result("rig"));
        assertEquals(output, f.apply("rig", plan));
        assertEquals(reads, f.reads);
        assertEquals(
                "OPERATION_ID_CONFLICT",
                f.apply("rig", Map.of("planId", "different")).get("code"));
    }

    @Test
    void reversePairAndUncombineThenMoveFollowSequentialPredictions() {
        var f = new Fixture();
        var out = f.apply("pair", f.plan(move("b", group("root"), 1), combine("b", "a")));
        assertEquals("APPLIED", out.get("outcome"), out.toString());
        assertEquals(List.of("p:b", "p:a", "g:folder", "p:c"), f.groups.get("root").children);
        assertEquals("INVALID_ARGUMENT", f.plan(combine("b", "c")).get("error"));
        assertEquals("INVALID_ARGUMENT", f.plan(move("a", group("folder"), 0)).get("error"));
        assertEquals("INVALID_ARGUMENT", f.plan(move("c", group("root"), 1)).get("error"));
        assertEquals(
                "APPLIED",
                f.apply("unpair", f.plan(uncombine("a"), move("a", group("folder"), 0)))
                        .get("outcome"));
        assertNull(f.params.get("b").partner);
        assertEquals(List.of("p:a"), f.groups.get("folder").children);
    }

    @Test
    void deleteRequiresExactSubtreeAndParameterDisclosureAndNativeReadback() {
        var f = new Fixture();
        assertEquals(
                "INVALID_ARGUMENT",
                f.plan(remove("folder", List.of("folder"), List.of())).get("error"));
        assertEquals(
                "INVALID_ARGUMENT",
                f.plan(remove("root", List.of("root", "folder"), List.of("a", "b", "c")))
                        .get("error"));
        var plan = f.plan(remove("folder", List.of("folder"), List.of("b")));
        assertEquals(
                List.of(Map.of(
                        "kind",
                        "remove_group",
                        "deletedGroupIds",
                        List.of("folder"),
                        "deletedParameterIds",
                        List.of("b"))),
                plan.get("effects"));
        assertEquals("APPLIED", f.apply("delete", plan).get("outcome"));
        assertFalse(f.params.containsKey("b"));
        assertFalse(f.groups.containsKey("folder"));
        assertEquals(List.of("a", "c"), new ArrayList<>(f.params.keySet()));
    }

    @Test
    void nestedSubtreeDeletionHasPreorderDisclosureAndPreservesOtherRoots() {
        var f = new Fixture();
        f.groups.put("nested", new G("Nested", "folder", new ArrayList<>(List.of("p:b"))));
        f.groups.get("folder").children = new ArrayList<>(List.of("g:nested"));
        assertEquals(
                "INVALID_ARGUMENT",
                f.plan(remove("folder", List.of("nested", "folder"), List.of("b")))
                        .get("error"));
        assertEquals(
                "APPLIED",
                f.apply("nested", f.plan(remove("folder", List.of("folder", "nested"), List.of("b"))))
                        .get("outcome"));
        assertEquals(Set.of("root"), f.groups.keySet());
        assertEquals(List.of("p:a", "p:c"), f.groups.get("root").children);
    }

    @Test
    void noopsDoNotInvokeSettersOrCreateHistory() {
        var f = new Fixture();
        var out =
                f.apply("noop", f.plan(rename(group("folder"), "Folder"), move("a", group("root"), 0), uncombine("a")));
        assertEquals("NO_CHANGE", out.get("outcome"), out.toString());
        assertEquals(0, f.writes);
        assertEquals(0, f.revision);
    }

    @Test
    void staleFieldsAndAllHistoryBindingsAreRejectedBeforeWriting() {
        for (String change : List.of(
                "revision", "document", "model", "generation", "hd", "hm", "name", "order", "range", "combined")) {
            var f = new Fixture();
            var plan = f.plan(rename(group("folder"), "Changed"));
            switch (change) {
                case "revision" -> f.revision++;
                case "document" -> f.document = "other";
                case "model" -> f.modelId = "other";
                case "generation" -> f.generation++;
                case "hd" -> f.hd = "other";
                case "hm" -> f.hm = "other";
                case "name" -> f.groups.get("folder").name = "Other";
                case "order" -> Collections.reverse(f.groups.get("root").children);
                case "range" -> f.params.get("a").max = 2;
                default -> {
                    f.params.get("a").flag = null;
                }
            }
            assertEquals("STALE_STATE", f.apply("stale", plan).get("code"), change);
            assertEquals(0, f.writes);
            assertEquals(0, f.transactions);
        }
    }

    @Test
    void snapshotAndPlanSessionTtlAndBoundedEviction() {
        var f = new Fixture();
        var read = McpRequestRegistry.inSession("one", f::read);
        assertEquals("NOT_FOUND", f.planFrom(read, rename(group("folder"), "x")).get("error"));
        var p = f.plan(rename(group("folder"), "x"));
        f.now = McpParameterStructureDomain.TTL;
        assertEquals("NOT_FOUND", f.apply("expired", p).get("code"));
        var g = new Fixture();
        var first = g.plan(rename(group("folder"), "x"));
        for (int i = 0; i < 16; i++) g.plan(rename(group("folder"), "x" + i));
        assertEquals("NOT_FOUND", g.apply("evicted", first).get("code"));
        assertEquals(0, g.writes);
    }

    @Test
    void metadataUnknownAndAmbiguousIdsAreNotGuessed() {
        var f = new Fixture();
        f.params.get("a").flag = null;
        assertEquals(true, f.read().get("ok"));
        assertEquals("UNAVAILABLE", f.plan(move("a", group("folder"), 0)).get("error"));
        assertEquals(true, f.plan(rename(group("folder"), "Safe")).get("ok"));
        f.duplicateParameter = true;
        assertEquals("AMBIGUOUS_DISPLAY_ID", f.read().get("error"));
        f.duplicateParameter = false;
        f.duplicateGroup = true;
        assertEquals("AMBIGUOUS_DISPLAY_ID", f.read().get("error"));
        var g = new Fixture();
        g.groups.get("root").children.add("p:a");
        assertEquals("INVALID_STRUCTURE", g.read().get("error"));
        var h = new Fixture();
        h.params.get("a").flag = true;
        assertEquals("INVALID_STRUCTURE", h.read().get("error"));
    }

    @Test
    void preflightRejectsUnknownReferencesIllegalIndicesAndUnsupportedOperations() {
        var f = new Fixture();
        for (var op : List.of(
                move("a", group("root"), 3),
                move("a", Map.of("createdRef", "later"), 0),
                combine("a", "b"),
                combine("a", "a"),
                create("dup", "x"))) {
            var edits = op.get("kind").equals("create_group") ? List.of(op, op) : List.of(op);
            assertEquals("INVALID_ARGUMENT", f.plan(edits).get("error"), op.toString());
        }
        f.params.get("a").type = ParameterType.BLEND_SHAPE;
        assertEquals(
                "INVALID_ARGUMENT",
                f.plan(move("b", group("root"), 1), combine("a", "b")).get("error"));
        assertEquals(0, f.writes);
    }

    @Test
    void partialBatchFailureCorruptReadbackAndTransactionRaceRollback() {
        for (String mode : List.of("partial", "readback", "race")) {
            var f = new Fixture();
            var initial = f.copy();
            var p = f.plan(rename(group("folder"), "Changed"), move("a", group("folder"), 1));
            if (mode.equals("partial")) f.failAt = 2;
            if (mode.equals("readback")) f.afterWrite = () -> f.groups.get("root").name = "Corrupt";
            if (mode.equals("race")) f.before = () -> f.groups.get("root").name = "Race";
            var out = f.apply("failure", p);
            assertEquals("ROLLED_BACK", out.get("outcome"), out.toString());
            assertEquals(true, out.get("rollbackStateVerified"));
            assertEquals(initial, f.copy());
        }
    }

    @Test
    void recoveryFailureLostResponseAndCommittedReadbackWarningAreDistinct() {
        var f = new Fixture();
        var p = f.plan(rename(group("folder"), "Changed"));
        f.failAt = 1;
        f.recoveryFailed = true;
        assertEquals("OUTCOME_UNKNOWN", f.apply("recovery", p).get("outcome"));
        var g = new Fixture();
        p = g.plan(rename(group("folder"), "Changed"));
        g.loseResult = true;
        assertEquals("OUTCOME_UNKNOWN", g.apply("lost", p).get("outcome"));
        assertEquals("Changed", g.groups.get("folder").name);
        var h = new Fixture();
        p = h.plan(rename(group("folder"), "Changed"));
        h.afterCommit = () -> h.readFailure = true;
        assertEquals("APPLIED_WITH_READBACK_WARNING", h.apply("warning", p).get("outcome"));
        h.noReads = true;
        assertEquals("APPLIED_WITH_READBACK_WARNING", h.result("warning").get("outcome"));
    }

    @Test
    void unavailableTransactionAndResultStoreDoNotWrite() {
        var f = new Fixture();
        f.unavailable = true;
        assertEquals(
                "NOT_APPLIED",
                f.apply("unavailable", f.plan(rename(group("folder"), "Changed")))
                        .get("outcome"));
        assertEquals(0, f.writes);
        var g = new Fixture();
        var p = g.plan(rename(group("folder"), "Folder"));
        for (int i = 0; i < 256; i++)
            assertEquals("NO_CHANGE", g.apply("n" + i, p).get("outcome"));
        assertEquals("RESULT_STORE_FULL", g.apply("full", p).get("code"));
        assertEquals(0, g.writes);
        g.noReads = true;
        assertEquals("NO_CHANGE", g.result("n0").get("outcome"));
        assertEquals("OUTCOME_UNKNOWN", g.result("missing").get("outcome"));
    }

    @Test
    void cancellationBoundaryAndSchemaRejectsUnclosedPayloads() {
        var f = new Fixture();
        var registry = new McpRequestRegistry();
        try (var scope = registry.enter("cancel", 1)) {
            var p = f.plan(rename(group("folder"), "Changed"));
            registry.cancel("cancel", 1);
            assertThrows(java.util.concurrent.CancellationException.class, () -> f.apply("cancel", p));
            assertEquals(0, f.writes);
        }
        var g = new Fixture();
        try (var scope = registry.enter("after", 2)) {
            var p = g.plan(rename(group("folder"), "Changed"));
            g.afterWrite = () -> registry.cancel("after", 2);
            assertEquals("APPLIED", g.apply("after", p).get("outcome"));
        }
        var bad = new HashMap<String, Object>();
        bad.put("documentId", "doc");
        bad.put("modelId", "model");
        bad.put("path", "/tmp/model");
        assertThrows(IllegalArgumentException.class, () -> g.call(McpParameterStructureDomain.READ, bad));
        var read = g.read();
        var args = new HashMap<String, Object>(Map.of(
                "documentId",
                "doc",
                "modelId",
                "model",
                "snapshotId",
                read.get("snapshotId"),
                "edits",
                List.of(Map.of("kind", "rename_group", "group", group("folder"), "name", "x", "invoke", "unsafe"))));
        assertThrows(IllegalArgumentException.class, () -> g.call(McpParameterStructureDomain.PLAN, args));
    }

    @Test
    void budgetsAndSnapshotImmutabilityAreEnforced() {
        var f = new Fixture();
        for (int i = 0; i < 126; i++) {
            String id = "g" + i;
            f.groups.put(id, new G("Group", "root", new ArrayList<>()));
            f.groups.get("root").children.add("g:" + id);
        }
        assertEquals(true, f.read().get("ok"), "128 folders are allowed");
        f.groups.put("excess", new G("Extra", "root", new ArrayList<>()));
        f.groups.get("root").children.add("g:excess");
        assertEquals("BUDGET_EXCEEDED", f.read().get("error"));
        var g = new Fixture();
        var op = new HashMap<String, Object>(rename(group("folder"), "Planned"));
        var p = g.plan(op);
        op.put("name", "Tampered");
        assertEquals("APPLIED", g.apply("immutable", p).get("outcome"));
        assertEquals("Planned", g.groups.get("folder").name);
    }

    @Test
    void staleInsideTransactionTtlAndDocumentSwitchCannotContinueTheBatch() {
        var f = new Fixture();
        var plan = f.plan(rename(group("folder"), "Changed"));
        f.before = () -> f.now = McpParameterStructureDomain.TTL;
        assertEquals("ROLLED_BACK", f.apply("ttl", plan).get("outcome"));
        assertEquals(0, f.writes);
        var g = new Fixture();
        plan = g.plan(rename(group("folder"), "Changed"), move("a", group("folder"), 1));
        g.afterWrite = () -> g.document = "other";
        var switched = g.apply("switch", plan);
        assertEquals("OUTCOME_UNKNOWN", switched.get("outcome"));
        assertEquals(false, switched.get("rollbackStateVerified"));
        assertEquals(1, g.writes, "second edit must never run against a changed document");
        assertEquals("Folder", g.groups.get("folder").name);
    }

    @Test
    void deletingAPairAndRepeatedPairRequestRespectExplicitStructuralScope() {
        var f = new Fixture();
        var first = f.apply("pair", f.plan(move("a", group("folder"), 0), combine("a", "b")));
        assertEquals("APPLIED", first.get("outcome"));
        int writes = f.writes;
        assertEquals("NO_CHANGE", f.apply("same", f.plan(combine("b", "a"))).get("outcome"));
        assertEquals(writes, f.writes);
        var deleted = f.apply("delete-pair", f.plan(remove("folder", List.of("folder"), List.of("a", "b"))));
        assertEquals("APPLIED", deleted.get("outcome"), deleted.toString());
        assertEquals(Set.of("c"), f.params.keySet());
    }

    @Test
    void byteAndParameterBudgetsDoNotIssueSnapshotsAndClosedSchemasRejectNegativeIndices() {
        var f = new Fixture();
        for (int i = 0; i < 509; i++) {
            String id = "p" + i;
            f.params.put(id, new P(id));
            f.groups.get("root").children.add("p:" + id);
        }
        assertEquals(true, f.read().get("ok"), "512 parameters are allowed");
        f.params.put("excess", new P("Extra"));
        f.groups.get("root").children.add("p:excess");
        assertEquals("BUDGET_EXCEEDED", f.read().get("error"));
        var g = new Fixture();
        for (int i = 0; i < 50; i++) {
            String id = "g" + i;
            g.groups.put(id, new G("n".repeat(4096), "root", new ArrayList<>()));
            g.groups.get("root").children.add("g:" + id);
        }
        var output = g.read();
        assertEquals("BUDGET_EXCEEDED", output.get("error"));
        assertFalse(output.containsKey("snapshotId"));
        var h = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> h.plan(move("a", group("root"), -1)));
        assertThrows(IllegalArgumentException.class, () -> h.plan(Map.of("kind", "move_group")));
        assertEquals(0, h.writes);
    }

    @Test
    void resultsAreSessionLocalAndPlansRetainOriginalSnapshotDeadline() {
        var f = new Fixture();
        var snapshot = f.read();
        f.now = McpParameterStructureDomain.TTL - 1;
        var plan = f.planFrom(snapshot, rename(group("folder"), "Changed"));
        assertEquals(true, plan.get("ok"));
        f.now++;
        assertEquals("NOT_FOUND", f.apply("late", plan).get("code"));
        var g = new Fixture();
        var out =
                McpRequestRegistry.inSession("owner", () -> g.apply("id", g.plan(rename(group("folder"), "Changed"))));
        assertEquals("APPLIED", out.get("outcome"));
        g.noReads = true;
        assertEquals("OUTCOME_UNKNOWN", g.result("id").get("outcome"));
        assertEquals(out, McpRequestRegistry.inSession("owner", () -> g.result("id")));
    }

    @Test
    void missingAdditiveSdkMethodsFailClosedAndDoNotLeaveRunningResults() {
        var f = new Fixture();
        f.missingMixedApi = true;
        assertEquals("UNAVAILABLE", f.read().get("error"));
        assertEquals(0, f.writes);
        var g = new Fixture();
        var original = g.copy();
        var plan = g.plan(rename(group("folder"), "Changed"), move("a", group("folder"), 1));
        g.missingIndexedApi = true;
        var result = g.apply("old-sdk", plan);
        assertEquals("ROLLED_BACK", result.get("outcome"), result.toString());
        assertEquals(true, result.get("rollbackStateVerified"));
        assertEquals(original, g.copy());
        g.noReads = true;
        assertEquals(result, g.result("old-sdk"));
        assertEquals(result, g.apply("old-sdk", plan));
    }

    @Test
    void actualIdsThatResembleProvisionalIdsRemainUsableWithoutCollisions() {
        var f = new Fixture();
        String id = "~created-group:existing";
        f.groups.put(id, new G("Real group", "root", new ArrayList<>()));
        f.groups.get("root").children.add("g:" + id);
        assertEquals(true, f.read().get("ok"));
        assertEquals("INVALID_ARGUMENT", f.plan(create("existing", "New")).get("error"));
        assertEquals(
                "APPLIED",
                f.apply("real-id", f.plan(rename(group(id), "Real renamed"), create("different", "New")))
                        .get("outcome"));
        assertEquals("Real renamed", f.groups.get(id).name);
        assertEquals(
                "APPLIED",
                f.apply("remove-real", f.plan(remove(id, List.of(id), List.of())))
                        .get("outcome"));
    }

    @Test
    void sameDisplayIdAndDefinitionWithDifferentNativeGuidIsStale() {
        var f = new Fixture();
        var read = f.read();
        var params = (List<?>) ((Map<?, ?>) read.get("structure")).get("parameters");
        assertEquals(f.params.get("a").guid, ((Map<?, ?>) params.get(0)).get("guid"));
        var plan = f.plan(rename(group("folder"), "Changed"));
        f.params.get("a").guid = UUID.randomUUID().toString();
        assertEquals("STALE_STATE", f.apply("recreated", plan).get("code"));
        assertEquals(0, f.writes);
    }

    @Test
    void duplicateNativeParameterGuidAndMissingReaderFailClosed() {
        var f = new Fixture();
        f.params.get("b").guid = f.params.get("a").guid;
        assertEquals(false, f.read().get("ok"));
        assertEquals(0, f.writes);
        var g = new Fixture();
        g.missingGuidApi = true;
        assertEquals("UNAVAILABLE", g.read().get("error"));
        assertEquals(0, g.writes);
    }

    static final class G {
        String name, parent;
        List<String> children;

        G(String name, String parent, List<String> children) {
            this.name = name;
            this.parent = parent;
            this.children = children;
        }

        G copy() {
            return new G(name, parent, new ArrayList<>(children));
        }

        public boolean equals(Object other) {
            return other instanceof G g
                    && Objects.equals(name, g.name)
                    && Objects.equals(parent, g.parent)
                    && children.equals(g.children);
        }

        public int hashCode() {
            return Objects.hash(name, parent, children);
        }
    }

    static final class P {
        String name, partner, guid;
        Boolean flag = false;
        float max = 1;
        ParameterType type = ParameterType.NORMAL;

        P(String name) {
            this.name = name;
            this.guid = UUID.randomUUID().toString();
        }

        P copy() {
            var p = new P(name);
            p.guid = guid;
            p.partner = partner;
            p.flag = flag;
            p.max = max;
            p.type = type;
            return p;
        }

        public boolean equals(Object other) {
            return other instanceof P p
                    && Objects.equals(guid, p.guid)
                    && Objects.equals(name, p.name)
                    && Objects.equals(partner, p.partner)
                    && Objects.equals(flag, p.flag)
                    && max == p.max
                    && type == p.type;
        }

        public int hashCode() {
            return Objects.hash(name, partner, guid, flag, max, type);
        }
    }

    record Copy(Map<String, G> groups, Map<String, P> params) {}

    static final class Fixture {
        final Map<String, G> groups = new LinkedHashMap<>();
        final Map<String, P> params = new LinkedHashMap<>();
        long now, revision, generation = 1;
        int writes, transactions, reads, nextGroup, failAt;
        String document = "doc", modelId = "model", hd = "hd", hm = "hm";
        boolean missingMixedApi, missingIndexedApi, missingGuidApi;
        boolean duplicateGroup, duplicateParameter, noReads, readFailure, recoveryFailed, loseResult, unavailable;
        Runnable before = () -> {}, afterWrite = () -> {}, afterCommit = () -> {};
        final CubismModel model = proxy(CubismModel.class, (name, args) -> switch (name) {
            case "parameterGroups" -> proxy(ParameterGroups.class, this::groupsCall);
            case "parameters" -> proxy(Parameters.class, this::paramsCall);
            default -> throw new AssertionError("Unexpected model call " + name);
        });
        final AuthoringTransactionService service = new AuthoringTransactionService() {
            public <T> AuthoringTransactionResult<T> execute(
                    AuthoringTransactionOptions options, AuthoringTransactionWork<T> work) {
                transactions++;
                if (unavailable) return AuthoringTransactionResult.unavailable("unavailable");
                var original = copy();
                var start = history();
                T value;
                try {
                    before.run();
                    value = work.run();
                } catch (Exception failure) {
                    var receipt =
                            new AuthoringTransactionReceipt("tx", options.label(), start, history(), Optional.empty());
                    if (recoveryFailed) return AuthoringTransactionResult.recoveryFailed(receipt, "injected");
                    restore(original);
                    return AuthoringTransactionResult.rolledBack(receipt, "injected");
                }
                boolean changed = !original.equals(copy());
                if (changed) revision++;
                var receipt = new AuthoringTransactionReceipt(
                        "tx", options.label(), start, history(), changed ? Optional.of("entry") : Optional.empty());
                afterCommit.run();
                if (loseResult) throw new IllegalStateException("lost");
                return changed
                        ? AuthoringTransactionResult.committed(value, receipt)
                        : AuthoringTransactionResult.noChange(value, receipt);
            }
        };
        final CubismFacade facade = proxy(CubismFacade.class, (name, args) -> {
            if (name.equals("authoringTransactions")) return service;
            throw new AssertionError(name);
        });
        final McpExecutionBridge execution = new McpExecutionBridge(new UiScheduler() {
            public Registration runOnUiThread(Runnable r) {
                r.run();
                return () -> {};
            }

            public Registration runOnUiThreadLater(Runnable r, Duration d) {
                throw new AssertionError();
            }
        });
        final McpToolCatalog tools = new McpParameterStructureDomain(
                        facade, execution, () -> new McpStoredAuthoringDomain.Context(model, binding()), () -> now)
                .tools();

        Fixture() {
            groups.put("root", new G("Root", null, new ArrayList<>(List.of("p:a", "g:folder", "p:c"))));
            groups.put("folder", new G("Folder", "root", new ArrayList<>(List.of("p:b"))));
            for (String id : List.of("a", "b", "c")) params.put(id, new P(id));
        }

        void readGuard() {
            if (noReads) throw new AssertionError("host read on result/replay");
            reads++;
            if (readFailure) throw new IllegalStateException("read failure");
        }

        List<String> order() {
            var out = new ArrayList<String>();
            var todo = new ArrayDeque<String>();
            todo.push("root");
            while (!todo.isEmpty()) {
                String id = todo.pop();
                out.add(id);
                var c = groups.get(id).children;
                for (int i = c.size() - 1; i >= 0; i--)
                    if (c.get(i).startsWith("g:")) todo.push(c.get(i).substring(2));
            }
            return out;
        }

        Object groupsCall(String name, Object[] args) {
            if (name.equals("addGroup")) {
                String id = "native-" + (++nextGroup);
                groups.put(id, new G((String) args[0], "root", new ArrayList<>()));
                groups.get("root").children.add("g:" + id);
                wrote();
                return groupHandle(id);
            }
            if (name.equals("removeGroup")) {
                String id = ((ParameterGroupId) args[0]).value();
                var deleted = new HashSet<String>();
                var pending = new ArrayDeque<String>();
                pending.add(id);
                while (!pending.isEmpty()) {
                    String g = pending.pop();
                    deleted.add(g);
                    for (String c : groups.get(g).children)
                        if (c.startsWith("g:")) pending.add(c.substring(2));
                        else params.remove(c.substring(2));
                }
                groups.get(groups.get(id).parent).children.remove("g:" + id);
                deleted.forEach(groups::remove);
                wrote();
                return null;
            }
            if (name.equals("moveParameter")) {
                if (missingIndexedApi) throw new NoSuchMethodError("moveParameter");
                String id = ((ParameterId) args[0]).value();
                for (var g : groups.values()) g.children.remove("p:" + id);
                groups.get(((ParameterGroupId) args[1]).value()).children.add((int) args[2], "p:" + id);
                wrote();
                return null;
            }
            readGuard();
            return switch (name) {
                case "all" -> {
                    var list = new ArrayList<>(
                            order().stream().map(this::groupHandle).toList());
                    if (duplicateGroup) list.add(groupHandle("folder"));
                    yield list;
                }
                case "root" -> groupHandle("root");
                case "find" -> groupHandle(((ParameterGroupId) args[0]).value());
                default -> throw new AssertionError(name);
            };
        }

        ParameterGroup groupHandle(String id) {
            if (!groups.containsKey(id)) throw new NoSuchElementException();
            return proxy(ParameterGroup.class, (name, args) -> {
                if (name.equals("rename")) {
                    groups.get(id).name = (String) args[0];
                    wrote();
                    return null;
                }
                if (name.equals("orderedChildren") && missingMixedApi) throw new NoSuchMethodError("orderedChildren");
                readGuard();
                var g = groups.get(id);
                return switch (name) {
                    case "id" -> new ParameterGroupId(id);
                    case "name" -> Optional.ofNullable(g.name);
                    case "parentId" -> Optional.ofNullable(g.parent).map(ParameterGroupId::new);
                    case "orderedChildren" ->
                        g.children.stream()
                                .map(v -> v.startsWith("g:")
                                        ? ParameterGroupChild.group(new ParameterGroupId(v.substring(2)))
                                        : ParameterGroupChild.parameter(new ParameterId(v.substring(2))))
                                .toList();
                    case "childGroupIds" ->
                        g.children.stream()
                                .filter(v -> v.startsWith("g:"))
                                .map(v -> new ParameterGroupId(v.substring(2)))
                                .toList();
                    case "parameterIds" ->
                        g.children.stream()
                                .filter(v -> v.startsWith("p:"))
                                .map(v -> new ParameterId(v.substring(2)))
                                .toList();
                    default -> throw new AssertionError(name);
                };
            });
        }

        Object paramsCall(String name, Object[] args) {
            readGuard();
            return switch (name) {
                case "all" -> {
                    var list = new ArrayList<>(
                            params.keySet().stream().map(this::paramHandle).toList());
                    if (duplicateParameter) list.add(paramHandle("a"));
                    yield list;
                }
                case "find" -> paramHandle(((ParameterId) args[0]).value());
                default -> throw new AssertionError(name);
            };
        }

        Parameter paramHandle(String id) {
            return proxy(Parameter.class, (name, args) -> {
                var p = params.get(id);
                if (p == null) throw new NoSuchElementException();
                if (name.equals("combineWith")) {
                    String other = ((ParameterId) args[0]).value();
                    var group = groups.values().stream()
                            .filter(g -> g.children.contains("p:" + id))
                            .findFirst()
                            .orElseThrow();
                    group.children.remove("p:" + other);
                    group.children.add(group.children.indexOf("p:" + id) + 1, "p:" + other);
                    p.flag = true;
                    p.partner = other;
                    params.get(other).flag = false;
                    params.get(other).partner = id;
                    wrote();
                    return null;
                }
                if (name.equals("uncombine")) {
                    var other = params.get(p.partner);
                    if (other != null) {
                        other.flag = false;
                        other.partner = null;
                    }
                    p.flag = false;
                    p.partner = null;
                    wrote();
                    return null;
                }
                readGuard();
                return switch (name) {
                    case "id" -> new ParameterId(id);
                    case "index" -> new ArrayList<>(params.keySet()).indexOf(id);
                    case "guid" -> {
                        if (missingGuidApi) throw new NoSuchMethodError("guid");
                        yield p.guid;
                    }
                    case "name" -> Optional.of(p.name);
                    case "type" -> p.type;
                    case "repeat" -> Optional.of(false);
                    case "combined" -> Optional.ofNullable(p.flag);
                    case "combinedWith" -> Optional.ofNullable(p.partner).map(ParameterId::new);
                    case "getMinimumValue", "getDefaultValue" -> 0f;
                    case "getMaximumValue" -> p.max;
                    default -> throw new AssertionError("Unexpected parameter call " + name);
                };
            });
        }

        void wrote() {
            writes++;
            afterWrite.run();
            if (failAt == writes) throw new IllegalStateException("injected after write");
        }

        Copy copy() {
            var gs = new LinkedHashMap<String, G>();
            groups.forEach((k, g) -> gs.put(k, g.copy()));
            var ps = new LinkedHashMap<String, P>();
            params.forEach((k, p) -> ps.put(k, p.copy()));
            return new Copy(gs, ps);
        }

        void restore(Copy value) {
            groups.clear();
            value.groups.forEach((k, g) -> groups.put(k, g.copy()));
            params.clear();
            value.params.forEach((k, p) -> params.put(k, p.copy()));
        }

        Map<String, Object> binding() {
            readGuard();
            return Map.of(
                    "documentId",
                    document,
                    "modelId",
                    modelId,
                    "historyGeneration",
                    generation,
                    "historyRevision",
                    revision,
                    "historyDocumentBinding",
                    hd,
                    "historyManagerBinding",
                    hm);
        }

        HistorySnapshot history() {
            return new HistorySnapshot(
                    HistorySnapshot.Availability.AVAILABLE, generation, revision, 0, List.of(), false, false, hd, hm);
        }

        Map<String, Object> read() {
            return call(McpParameterStructureDomain.READ, Map.of("documentId", "doc", "modelId", "model"));
        }

        @SafeVarargs
        final Map<String, Object> plan(Map<String, Object>... edits) {
            return plan(List.of(edits));
        }

        Map<String, Object> plan(List<Map<String, Object>> edits) {
            return planFrom(read(), edits);
        }

        Map<String, Object> planFrom(Map<String, Object> read, Map<String, Object> edit) {
            return planFrom(read, List.of(edit));
        }

        Map<String, Object> planFrom(Map<String, Object> read, List<Map<String, Object>> edits) {
            assertEquals(true, read.get("ok"), read.toString());
            return call(
                    McpParameterStructureDomain.PLAN,
                    Map.of(
                            "documentId",
                            "doc",
                            "modelId",
                            "model",
                            "snapshotId",
                            read.get("snapshotId"),
                            "edits",
                            edits));
        }

        Map<String, Object> apply(String id, Map<String, Object> plan) {
            return call(
                    McpParameterStructureDomain.APPLY,
                    Map.of("documentId", "doc", "modelId", "model", "planId", plan.get("planId"), "operationId", id));
        }

        Map<String, Object> result(String id) {
            return call(McpParameterStructureDomain.RESULT, Map.of("operationId", id));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> call(String name, Map<String, Object> args) {
            return (Map<String, Object>) tools.call(name, args).get("structuredContent");
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> calls) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> {
            if (m.getDeclaringClass() == Object.class)
                return switch (m.getName()) {
                    case "equals" -> p == a[0];
                    case "hashCode" -> System.identityHashCode(p);
                    default -> type.getSimpleName();
                };
            return calls.apply(m.getName(), a == null ? new Object[0] : a);
        });
    }
}
