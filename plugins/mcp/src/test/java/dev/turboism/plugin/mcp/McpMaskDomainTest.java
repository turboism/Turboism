package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.*;
import dev.turboism.sdk.cubism.clipmask.*;
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

class McpMaskDomainTest {
    @Test
    void cancellationBeforeAdmissionDoesNotWriteAndAfterAdmissionDoesNotClaimRollback() {
        var registry = new McpRequestRegistry();
        var f = new Fixture();
        try (var scope = registry.enter("before-mask", 1)) {
            var request = f.request("cancel", f.read("a", "b"), edit("a", List.of("b"), false));
            assertTrue(registry.cancel("before-mask", 1));
            assertThrows(java.util.concurrent.CancellationException.class, () -> f.write(request));
            assertEquals(0, f.writes);
        }
        var g = new Fixture();
        try (var scope = registry.enter("after-mask", 2)) {
            var request = g.request("after", g.read("a", "b"), edit("a", List.of("b"), false));
            g.afterWrite = () -> {
                assertEquals("RUNNING", g.result("after").get("outcome"));
                registry.cancel("after-mask", 2);
            };
            assertEquals("APPLIED", g.write(request).get("outcome"));
            assertEquals(1, g.writes);
        }
        assertEquals(
                "APPLIED",
                McpRequestRegistry.inSession("after-mask", () -> g.result("after"))
                        .get("outcome"));
    }

    @Test
    void existingCyclesRemainReadableAndInversionDoesNotIntroduceNewEdges() {
        var f = new Fixture();
        f.objects.put("a", new ObjectState("guid-a", List.of("b"), false, null));
        f.objects.put("b", new ObjectState("guid-b", List.of("a"), false, null));
        var read = f.read("a");
        assertEquals(true, read.get("ok"));
        assertEquals(
                "APPLIED",
                f.write(f.request("existing-cycle", read, edit("a", List.of("b"), true)))
                        .get("outcome"));
    }

    @Test
    void metadataByteBudgetIsEnforcedBeforeIssuingSnapshot() {
        var f = new Fixture();
        for (String id : List.of("a", "b", "c"))
            f.objects.put(id, new ObjectState("g".repeat(4096), List.of(), false, null));
        var ids = new ArrayList<String>();
        for (int i = 0; i < 20; i++) {
            String id = "large" + i;
            ids.add(id);
            f.objects.put(id, new ObjectState("g".repeat(4096), List.of(), false, null));
        }
        var output = f.read(ids.toArray(String[]::new));
        assertEquals("BUDGET_EXCEEDED", output.get("error"));
        assertFalse(output.containsKey("snapshotId"));
        assertEquals(0, f.writes);
    }

    @Test
    void readExpandsDependenciesWithoutMutationAndCapturesGuidParents() {
        var f = new Fixture();
        f.objects.put("a", new ObjectState("guid-a", List.of("b"), true, "parent"));
        var read = f.read("a");
        assertEquals(true, read.get("ok"), read.toString());
        var objects = (List<?>) ((Map<?, ?>) read.get("snapshot")).get("objects");
        assertEquals(2, objects.size());
        var first = (Map<?, ?>) objects.get(0);
        assertEquals("guid-a", first.get("sourceGuid"));
        assertEquals("parent", first.get("parentDeformerId"));
        assertEquals(true, first.get("inverted"));
        assertEquals(0, f.writes);
        assertFalse(f.tools.registration(McpMaskDomain.READ).transactionEligible());
        assertEquals(
                McpExecutionAffinity.DIRECT,
                f.tools.registration(McpMaskDomain.RESULT).affinity());
    }

    @Test
    void conditionalBatchCommitsOnceReadbacksAndSupportsResultReplay() {
        var f = new Fixture();
        var original = Map.copyOf(f.objects);
        var request =
                f.request("op", f.read("a", "b", "c"), edit("a", List.of("b"), true), edit("b", List.of("c"), false));
        var output = f.write(request);
        assertEquals("APPLIED", output.get("outcome"), output.toString());
        assertEquals(true, output.get("readbackVerified"));
        assertNotNull(output.get("receipt"));
        assertEquals(1, f.transactions);
        assertEquals(1, f.writes);
        assertEquals(List.of("b"), f.objects.get("a").masks);
        assertTrue(f.objects.get("a").inverted);
        assertEquals(output, f.result("op"));
        assertEquals(output, f.write(request));
        assertEquals(1, f.writes);
        var after = Map.copyOf(f.objects);
        f.objects.clear();
        f.objects.putAll(original);
        f.revision++;
        assertEquals(output, f.result("op"));
        f.objects.clear();
        f.objects.putAll(after);
        f.revision++;
        assertEquals(after, f.objects);
    }

    @Test
    void unchangedMaskPlanSkipsSetterAndCreatesNoHistoryEntry() {
        var f = new Fixture();
        f.objects.put("a", new ObjectState("guid-a", List.of("b"), false, null));
        var output = f.write(f.request("noop", f.read("a"), edit("a", List.of("b"), false)));
        assertEquals("NO_CHANGE", output.get("outcome"), output.toString());
        assertEquals(0, f.writes);
        assertNull(((Map<?, ?>) output.get("receipt")).get("historyEntryId"));
    }

    @Test
    void changedDependencyAtSameRevisionAndDeleteRecreateAreStale() {
        for (String change : List.of("mask", "guid", "parent", "history", "document")) {
            var f = new Fixture();
            var request = f.request("stale", f.read("a", "b"), edit("a", List.of("b"), false));
            switch (change) {
                case "mask" -> f.objects.put("b", new ObjectState("guid-b", List.of("c"), false, null));
                case "guid" -> f.objects.put("b", new ObjectState("recreated", List.of(), false, null));
                case "parent" -> f.objects.put("b", new ObjectState("guid-b", List.of(), false, "parent"));
                case "history" -> f.revision++;
                default -> f.document = "other";
            }
            assertEquals("STALE_STATE", f.write(request).get("code"), change);
            assertEquals(0, f.writes);
            assertEquals(0, f.transactions);
        }
    }

    @Test
    void validatesAgainInsideTransactionBeforeMutation() {
        var f = new Fixture();
        var request = f.request("race", f.read("a", "b"), edit("a", List.of("b"), false));
        f.before = () -> f.objects.put("b", new ObjectState("changed", List.of(), false, null));
        assertEquals("ROLLED_BACK", f.write(request).get("outcome"));
        assertEquals(0, f.writes);
    }

    @Test
    void partialBatchFailureAndUnrelatedScopedChangeForceRollback() {
        for (boolean corrupt : List.of(false, true)) {
            var f = new Fixture();
            var original = Map.copyOf(f.objects);
            var request = f.request(
                    "fail", f.read("a", "b", "c"), edit("a", List.of("b"), false), edit("b", List.of("c"), true));
            if (corrupt) f.afterWrite = () -> f.objects.put("c", new ObjectState("changed", List.of(), false, null));
            else f.failAfterFirst = true;
            assertEquals("ROLLED_BACK", f.write(request).get("outcome"));
            assertEquals(original, f.objects);
        }
    }

    @Test
    void lostCommitIsUnknownNeverReplayedAndPostCommitReadFailureIsWarning() {
        var f = new Fixture();
        var request = f.request("lost", f.read("a", "b"), edit("a", List.of("b"), false));
        f.loseResult = true;
        var output = f.write(request);
        assertEquals("OUTCOME_UNKNOWN", output.get("outcome"));
        assertEquals(List.of("b"), f.objects.get("a").masks);
        assertEquals(output, f.write(request));
        assertEquals(1, f.writes);
        var g = new Fixture();
        request = g.request("warn", g.read("a", "b"), edit("a", List.of("b"), false));
        g.afterCommit = () -> g.readFailure = new IllegalStateException("native private path");
        output = g.write(request);
        assertEquals("APPLIED_WITH_READBACK_WARNING", output.get("outcome"));
        assertFalse((Boolean) output.get("readbackVerified"));
        assertNotNull(output.get("receipt"));
        assertFalse(output.toString().contains("native private path"));
    }

    @Test
    void recoveryFailureIsUnknownAndUnavailableServiceNeverCallsSetter() {
        var f = new Fixture();
        var request = f.request("recovery", f.read("a", "b"), edit("a", List.of("b"), false));
        f.failAfterFirst = true;
        f.recoveryFailed = true;
        assertEquals("OUTCOME_UNKNOWN", f.write(request).get("outcome"));
        var g = new Fixture();
        request = g.request("unavailable", g.read("a", "b"), edit("a", List.of("b"), false));
        g.unavailable = true;
        assertEquals("NOT_APPLIED", g.write(request).get("outcome"));
        assertEquals(0, g.writes);
    }

    @Test
    void emptyDuplicateSelfUnknownSourcesAndCyclesRejectWithoutWriting() {
        for (List<Map<String, Object>> edits : List.of(
                List.of(edit("a", List.of(), false)),
                List.of(edit("a", List.of("a"), false)),
                List.of(edit("a", List.of("b", "b"), false)),
                List.of(edit("a", List.of("unknown"), false)),
                List.of(edit("a", List.of("b"), false), edit("b", List.of("a"), false)),
                List.of(edit("a", List.of("b"), false), edit("a", List.of("b"), false)))) {
            var f = new Fixture();
            var request = f.request("bad", f.read("a", "b"), edits.toArray(new Map[0]));
            if (((List<?>) edits.get(0).get("maskIds")).isEmpty())
                assertThrows(IllegalArgumentException.class, () -> f.write(request));
            else assertEquals("NOT_APPLIED", f.write(request).get("outcome"));
            assertEquals(0, f.writes);
            assertEquals(0, f.transactions);
        }
    }

    @Test
    void snapshotsAndResultsAreSessionIsolatedAndExpiredTokensFail() {
        var f = new Fixture();
        var read = McpRequestRegistry.inSession("one", () -> f.read("a", "b"));
        var request = f.request("session", read, edit("a", List.of("b"), false));
        assertEquals(
                "NOT_FOUND",
                McpRequestRegistry.inSession("two", () -> f.write(request)).get("code"));
        assertEquals(
                "APPLIED",
                McpRequestRegistry.inSession("one", () -> f.write(request)).get("outcome"));
        assertEquals("OUTCOME_UNKNOWN", f.result("session").get("outcome"));
        var g = new Fixture();
        var expired = g.request("expired", g.read("a", "b"), edit("a", List.of("b"), false));
        g.now = McpMaskDomain.TTL;
        assertEquals("NOT_FOUND", g.write(expired).get("code"));
    }

    @Test
    void conflictingOperationIdAndCacheRetentionDoNotReplay() {
        var f = new Fixture();
        var read = f.read("a", "b");
        var request = f.request("same", read, edit("a", List.of("b"), false));
        assertEquals("APPLIED", f.write(request).get("outcome"));
        var conflict = f.request("same", read, edit("a", List.of("b"), true));
        assertEquals("OPERATION_ID_CONFLICT", f.write(conflict).get("code"));
        assertEquals(1, f.writes);
        var g = new Fixture();
        g.objects.put("a", new ObjectState("guid-a", List.of("b"), false, null));
        read = g.read("a");
        for (int i = 0; i < McpMaskDomain.MAX_OPERATIONS; i++)
            assertEquals(
                    "NO_CHANGE",
                    g.write(g.request("op" + i, read, edit("a", List.of("b"), false)))
                            .get("outcome"));
        assertEquals(
                "RESULT_STORE_FULL",
                g.write(g.request("overflow", read, edit("a", List.of("b"), false)))
                        .get("code"));
        assertEquals("NO_CHANGE", g.result("op0").get("outcome"));
        assertEquals(0, g.writes);
    }

    @Test
    void snapshotBoundEvictionAndDependencyBudgetAreExplicit() {
        var f = new Fixture();
        var old = f.request("evicted", f.read("a", "b"), edit("a", List.of("b"), false));
        for (int i = 0; i < 16; i++) f.read("a");
        assertEquals("NOT_FOUND", f.write(old).get("code"));
        var g = new Fixture();
        for (int i = 0; i < 65; i++)
            g.objects.put("n" + i, new ObjectState("g" + i, i == 64 ? List.of() : List.of("n" + (i + 1)), false, null));
        assertEquals("BUDGET_EXCEEDED", g.read("n0").get("error"));
    }

    @Test
    void permissionAndReadErrorsAreRedactedAndReadNeverInvokesGeometry() {
        for (RuntimeException failure : List.of(
                new SecurityException("private path"),
                new UnsupportedOperationException("private path"),
                new IllegalStateException("private path"))) {
            var f = new Fixture();
            f.readFailure = failure;
            var read = f.read("a");
            assertEquals(false, read.get("ok"));
            assertFalse(read.toString().contains("private path"));
        }
    }

    static Map<String, Object> edit(String id, List<String> masks, boolean invert) {
        return Map.of("objectId", id, "maskIds", masks, "inverted", invert);
    }

    private record ObjectState(String guid, List<String> masks, boolean inverted, String parent) {}

    private static final class Fixture {
        long now, revision = 1;
        String document = "doc";
        int writes, transactions;
        boolean failAfterFirst, loseResult, recoveryFailed, unavailable;
        RuntimeException readFailure;
        Runnable before = () -> {}, afterWrite = () -> {}, afterCommit = () -> {};
        final Map<String, ObjectState> objects = new LinkedHashMap<>();
        final CubismModel model = proxy(CubismModel.class, (name, args) -> {
            if (name.equals("drawables"))
                return proxy(Drawables.class, (m, a) -> {
                    if (!m.equals("find")) throw new AssertionError(m);
                    String id = ((ArtMeshId) a[0]).value();
                    if (!objects.containsKey(id)) throw new NoSuchElementException();
                    return proxy(Drawable.class, (n, v) -> {
                        if (readFailure != null) throw readFailure;
                        var state = objects.get(id);
                        return switch (n) {
                            case "guid" -> state.guid;
                            case "maskIds" ->
                                state.masks.stream().map(ArtMeshId::new).toList();
                            case "invertedMask" -> state.inverted;
                            case "parentPartId" -> Optional.empty();
                            case "parentDeformerId" ->
                                Optional.ofNullable(state.parent).map(DeformerId::new);
                            default -> throw new AssertionError("Unexpected SDK call " + n);
                        };
                    });
                });
            if (name.equals("replaceArtMeshClipMasks")) {
                writes++;
                int count = 0;
                for (var edit : (List<ClipMaskReplacement>) args[0]) {
                    var old = objects.get(edit.targetArtMeshId().value());
                    assertEquals(
                            old.masks,
                            edit.expectedMaskArtMeshIds().stream()
                                    .map(ArtMeshId::value)
                                    .toList());
                    assertEquals(old.inverted, edit.expectedInverted());
                    objects.put(
                            edit.targetArtMeshId().value(),
                            new ObjectState(
                                    old.guid,
                                    edit.replacementMaskArtMeshIds().stream()
                                            .map(ArtMeshId::value)
                                            .toList(),
                                    edit.replacementInverted(),
                                    old.parent));
                    if (failAfterFirst && ++count == 1) throw new IllegalStateException("partial");
                }
                afterWrite.run();
                return null;
            }
            throw new AssertionError(name);
        });
        final AuthoringTransactionService service = new AuthoringTransactionService() {
            public <T> AuthoringTransactionResult<T> execute(
                    AuthoringTransactionOptions options, AuthoringTransactionWork<T> work) {
                transactions++;
                if (unavailable) return AuthoringTransactionResult.unavailable("simulated.unavailable");
                var original = Map.copyOf(objects);
                var start = history();
                T value;
                try {
                    before.run();
                    value = work.run();
                } catch (Exception failure) {
                    var receipt =
                            new AuthoringTransactionReceipt("tx", options.label(), start, history(), Optional.empty());
                    if (recoveryFailed)
                        return AuthoringTransactionResult.recoveryFailed(receipt, "simulated.recovery-failed");
                    objects.clear();
                    objects.putAll(original);
                    return AuthoringTransactionResult.rolledBack(receipt, "simulated.rollback");
                }
                boolean changed = !original.equals(objects);
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
        final McpToolCatalog tools = new McpMaskDomain(
                        facade, execution, () -> new McpStoredAuthoringDomain.Context(model, binding()), () -> now)
                .tools();

        Fixture() {
            for (String id : List.of("a", "b", "c"))
                objects.put(id, new ObjectState("guid-" + id, List.of(), false, null));
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

        Map<String, Object> read(String... ids) {
            return call(McpMaskDomain.READ, Map.of("documentId", "doc", "modelId", "model", "objectIds", List.of(ids)));
        }

        @SafeVarargs
        final Map<String, Object> request(String operation, Map<String, Object> read, Map<String, Object>... edits) {
            assertEquals(true, read.get("ok"), read.toString());
            return Map.of(
                    "documentId",
                    "doc",
                    "modelId",
                    "model",
                    "snapshotId",
                    read.get("snapshotId"),
                    "operationId",
                    operation,
                    "replacements",
                    List.of(edits));
        }

        Map<String, Object> write(Map<String, Object> args) {
            return call(McpMaskDomain.WRITE, args);
        }

        Map<String, Object> result(String id) {
            return call(McpMaskDomain.RESULT, Map.of("operationId", id));
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
