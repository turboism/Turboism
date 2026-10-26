package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.*;
import dev.turboism.sdk.cubism.command.*;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

class McpDocumentLifecycleDomainTest {
    static DocumentSnapshot doc(String id, String modelId) {
        return new DocumentSnapshot(
                id,
                "Model " + id,
                "omitted",
                Optional.empty(),
                modelId == null
                        ? Optional.empty()
                        : Optional.of(new ModelSnapshot(modelId, "Model", List.of(), List.of(), List.of(), List.of())),
                modelId == null ? DocumentKind.IMAGE : DocumentKind.MODEL,
                Optional.of("content-" + id),
                Optional.empty());
    }

    @Test
    void discoversEmptyAndMixedDocumentsWithoutFilesystemPathsOrChanges() {
        var f = new Fixture();
        f.docs.add(doc("image", null));
        var read = f.read();
        assertEquals(true, read.get("ok"), read.toString());
        var state = (Map<?, ?>) read.get("state");
        var project = (Map<?, ?>) state.get("project");
        assertEquals(2, ((List<?>) project.get("documents")).size());
        assertFalse(read.toString().contains("should-not-appear"));
        assertFalse(read.toString().contains("filePath"));
        assertEquals(0, f.commands);
        var e = new Fixture();
        e.docs.clear();
        e.active = null;
        e.projectId = null;
        var empty = e.read();
        assertEquals(true, empty.get("ok"));
        assertNull(((Map<?, ?>) empty.get("state")).get("project"));
        assertNull(((Map<?, ?>) empty.get("state")).get("activeDocument"));
    }

    @Test
    void createsOnceAndReturnsActuallyObservedNewIdentityAndAuthoringBinding() {
        var f = new Fixture();
        var request = f.request("new", f.read());
        var out = f.create(request);
        assertEquals("READY", out.get("outcome"), out.toString());
        assertEquals(true, out.get("authoringReady"));
        assertEquals(false, out.get("existingModelContentsVerified"));
        assertEquals(false, out.get("persistenceVerified"));
        assertEquals(
                "created", ((Map<?, ?>) ((Map<?, ?>) out.get("afterState")).get("activeDocument")).get("documentId"));
        assertEquals(1, f.commands);
        f.noReads = true;
        assertEquals(out, f.result("new"));
        assertEquals(out, f.create(request));
        var changed = new LinkedHashMap<>(request);
        changed.put("snapshotId", "other");
        assertEquals("OPERATION_ID_CONFLICT", f.create(changed).get("error"));
    }

    @Test
    void createsFromNoDocumentOrProjectWithoutInventingOldIdentity() {
        var f = new Fixture();
        f.docs.clear();
        f.active = null;
        f.projectId = null;
        assertEquals("READY", f.create(f.request("empty", f.read())).get("outcome"));
    }

    @Test
    void commandExecutedNoopOrImageDocumentIsNeverReady() {
        for (String mode : List.of("noop", "image", "reuse-model", "two", "replace-old", "replace-project")) {
            var f = new Fixture();
            f.mode = mode;
            var out = f.create(f.request("notready", f.read()));
            assertEquals(
                    mode.equals("reuse-model") ? "OUTCOME_UNKNOWN" : "EXECUTED_NOT_READY",
                    out.get("outcome"),
                    mode + out);
            assertEquals(false, out.get("authoringReady"));
            assertEquals(false, out.get("ok"));
            assertEquals(1, f.commands);
        }
    }

    @Test
    void unavailableAuthoringDoesNotPreventInventoryButPreventsReadiness() {
        var f = new Fixture();
        f.authoringUnavailable = true;
        assertEquals(true, f.read().get("ok"));
        var out = f.create(f.request("noauthoring", f.read()));
        assertEquals("EXECUTED_NOT_READY", out.get("outcome"));
        assertNull(((Map<?, ?>) out.get("afterState")).get("authoringState"));
    }

    @Test
    void staleMetadataAndActiveHistoryStopBeforeDispatch() {
        for (String change : List.of("document", "history", "project", "name", "contents")) {
            var f = new Fixture();
            var req = f.request("stale", f.read());
            switch (change) {
                case "document" -> {
                    f.active = doc("other", "other-model");
                    f.docs.add(f.active);
                }
                case "history" -> f.revision++;
                case "project" -> f.projectId = "other";
                case "name" -> {
                    f.active = new DocumentSnapshot(
                            "old",
                            "Renamed",
                            "omitted",
                            Optional.empty(),
                            f.active.model(),
                            DocumentKind.MODEL,
                            f.active.contentId(),
                            Optional.empty());
                    f.docs.set(0, f.active);
                }
                case "contents" -> f.contentSuffix = "changed";
            }
            assertEquals("STALE_STATE", f.create(req).get("code"), change);
            assertEquals(0, f.commands);
        }
    }

    @Test
    void checksStateAgainAfterAvailabilityAndRejectsExpiredQueue() {
        var f = new Fixture();
        var req = f.request("race", f.read());
        f.onAvailable = () -> f.revision++;
        assertEquals("STALE_STATE", f.create(req).get("code"));
        assertEquals(0, f.commands);
        var g = new Fixture();
        var r = g.request("expired", g.read());
        g.beforeUi = () -> g.now = McpDocumentLifecycleDomain.TTL;
        assertEquals("STALE_STATE", g.create(r).get("code"));
        assertEquals(0, g.commands);
    }

    @Test
    void unavailableAndRejectedNativeCommandDoNotClaimCreation() {
        for (boolean service : List.of(false, true)) {
            var f = new Fixture();
            var req = f.request("unavailable", f.read());
            f.available = service;
            f.advertised = false;
            assertEquals("UNAVAILABLE", f.create(req).get("code"));
            assertEquals(0, f.commands);
        }
        var f = new Fixture();
        f.status = EditorCommandResult.Status.REJECTED;
        assertEquals("NOT_APPLIED", f.create(f.request("reject", f.read())).get("outcome"));
    }

    @Test
    void failedExceptionAndWrongCommandRemainUnknownAndCannotBeRepeated() {
        for (String failure : List.of("failed", "exception", "mismatch")) {
            var f = new Fixture();
            f.mode = failure;
            var req = f.request("unknown", f.read());
            var out = f.create(req);
            assertEquals("OUTCOME_UNKNOWN", out.get("outcome"), failure + out);
            assertFalse(out.toString().contains("secret"));
            f.noReads = true;
            assertEquals(out, f.create(req));
            assertEquals(out, f.result("unknown"));
            assertEquals(1, f.commands);
        }
    }

    @Test
    void postCommandUnreadableOrInconsistentStateIsUnknown() {
        for (boolean inconsistent : List.of(false, true)) {
            var f = new Fixture();
            f.afterCommand = () -> {
                if (inconsistent) f.active = doc("foreign", "other");
                else f.readFailure = true;
            };
            var out = f.create(f.request("readfail", f.read()));
            assertEquals("OUTCOME_UNKNOWN", out.get("outcome"));
            assertEquals("POST_COMMAND_READ_FAILED", out.get("code"));
        }
    }

    @Test
    void snapshotsAndResultsAreSessionBoundAndExpireWithoutHostReadsOnReplay() {
        var f = new Fixture();
        var snapshot = McpRequestRegistry.inSession("owner", f::read);
        assertEquals(
                "SNAPSHOT_NOT_FOUND", f.create(f.request("foreign", snapshot)).get("error"));
        f.now = McpDocumentLifecycleDomain.TTL;
        assertEquals(
                "SNAPSHOT_NOT_FOUND",
                McpRequestRegistry.inSession("owner", () -> f.create(f.request("expired", snapshot)))
                        .get("error"));
        var g = new Fixture();
        var out = McpRequestRegistry.inSession("owner", () -> g.create(g.request("private", g.read())));
        g.noReads = true;
        assertEquals("OUTCOME_UNKNOWN", g.result("private").get("outcome"));
        assertEquals(out, McpRequestRegistry.inSession("owner", () -> g.result("private")));
    }

    @Test
    void boundedSnapshotsEvictAndModelDirectoryRejectsDuplicateIdentities() {
        var f = new Fixture();
        var first = f.read();
        for (int i = 0; i < 16; i++) f.read();
        assertEquals("SNAPSHOT_NOT_FOUND", f.create(f.request("evicted", first)).get("error"));
        var d = new Fixture();
        d.docs.add(d.active);
        assertEquals("STALE_STATE", d.read().get("error"));
        var m = new Fixture();
        m.docs.add(doc("duplicate-model", "model-old"));
        assertEquals("STALE_STATE", m.read().get("error"));
    }

    @Test
    void documentAndEncodedBudgetsRejectRatherThanTruncate() {
        var f = new Fixture();
        for (int i = 0; i < 128; i++) f.docs.add(doc("d" + i, "m" + i));
        assertEquals("BUDGET_EXCEEDED", f.read().get("error"));
        var g = new Fixture();
        for (int i = 0; i < 60; i++) {
            var d = doc("d" + i, "m" + i);
            g.docs.add(new DocumentSnapshot(
                    d.documentId(),
                    "x".repeat(4096),
                    d.relativePath(),
                    Optional.empty(),
                    d.model(),
                    d.kind(),
                    d.contentId(),
                    Optional.empty()));
        }
        assertEquals("BUDGET_EXCEEDED", g.read().get("error"));
    }

    @Test
    void queuedAndRunningQueriesDoNotWaitOrReadHost() {
        var f = new Fixture();
        var request = f.request("running", f.read());
        f.beforeUi = () -> {
            int count = f.reads;
            assertEquals("QUEUED", f.result("running").get("outcome"));
            assertEquals("QUEUED", f.create(request).get("outcome"));
            assertEquals(count, f.reads);
        };
        f.duringCommand = () -> {
            int count = f.reads;
            assertEquals("RUNNING", f.result("running").get("outcome"));
            assertEquals("RUNNING", f.create(request).get("outcome"));
            assertEquals(count, f.reads);
        };
        assertEquals("READY", f.create(request).get("outcome"));
        assertEquals(1, f.commands);
    }

    @Test
    void schemaRejectsPathsArbitraryCommandsAndMetadataIsNotTransactionEligible() {
        var f = new Fixture();
        var request = f.request("schema", f.read());
        for (String key : List.of("path", "grantId", "commandId", "name", "width")) {
            var bad = new LinkedHashMap<>(request);
            bad.put(key, "unsafe");
            assertThrows(IllegalArgumentException.class, () -> f.create(bad));
        }
        for (String name : List.of(
                McpDocumentLifecycleDomain.READ,
                McpDocumentLifecycleDomain.CREATE,
                McpDocumentLifecycleDomain.RESULT)) {
            assertTrue(f.tools.registration(name).exactOutputSchema());
            assertFalse(f.tools.registration(name).transactionEligible());
        }
        assertEquals(
                McpOperationEffect.EXTERNAL_SIDE_EFFECT,
                f.tools.registration(McpDocumentLifecycleDomain.CREATE).effect());
        assertEquals(
                McpExecutionAffinity.DIRECT,
                f.tools.registration(McpDocumentLifecycleDomain.RESULT).affinity());
        assertEquals(0, f.commands);
    }

    @Test
    void queueCancellationPreventsDispatchAndPostDispatchCancellationDoesNotUndoCreation() {
        var f = new Fixture();
        var req = f.request("canceled", f.read());
        f.beforeUi = () -> {
            throw new java.util.concurrent.CancellationException();
        };
        assertEquals("NOT_APPLIED", f.create(req).get("outcome"));
        assertEquals(0, f.commands);
        var registry = new McpRequestRegistry();
        var g = new Fixture();
        try (var scope = registry.enter("owner", 1)) {
            var request = g.request("committed", g.read());
            g.duringCommand = () -> registry.cancel("owner", 1);
            assertEquals("READY", g.create(request).get("outcome"));
            assertEquals(1, g.commands);
        }
    }

    @Test
    void resultStoreNeverEvictsToEnableReexecution() {
        var f = new Fixture();
        f.mode = "noop";
        var snapshot = f.read();
        Map<String, Object> first = null;
        for (int i = 0; i < 128; i++) {
            var out = f.create(f.request("id" + i, snapshot));
            assertEquals("EXECUTED_NOT_READY", out.get("outcome"));
            if (i == 0) first = out;
        }
        assertEquals(
                "RESULT_STORE_FULL", f.create(f.request("excess", snapshot)).get("error"));
        f.noReads = true;
        assertEquals(first, f.create(f.request("id0", snapshot)));
        assertEquals(128, f.commands);
    }

    @Test
    void directoryReadDetectsChangingActiveMetadataAndExistingContentChangesPreventReadiness() {
        var f = new Fixture();
        f.afterCommand = () -> f.contentSuffix = "renamed";
        assertEquals(
                "EXECUTED_NOT_READY", f.create(f.request("metadata", f.read())).get("outcome"));
        var g = new Fixture();
        g.docs.add(doc("other", "model-other"));
        g.active = doc("not-in-project", "foreign");
        assertEquals("STALE_STATE", g.read().get("error"));
        assertEquals(0, g.commands);
    }

    @Test
    void readableExactDirectoryLimitReservesSpaceBeforeCreating() {
        var f = new Fixture();
        for (int i = 0; i < 127; i++) f.docs.add(doc("d" + i, "m" + i));
        var read = f.read();
        assertEquals(true, read.get("ok"));
        assertEquals("BUDGET_EXCEEDED", f.create(f.request("full", read)).get("code"));
        assertEquals(0, f.commands);
        var g = new Fixture();
        g.projectId = null;
        var partial = g.read();
        assertEquals(true, partial.get("ok"));
        assertEquals(
                "INCOMPLETE_DOCUMENT_INVENTORY",
                g.create(g.request("partial", partial)).get("code"));
        assertEquals(0, g.commands);
    }

    static final class Fixture {
        List<DocumentSnapshot> docs = new ArrayList<>();
        DocumentSnapshot active = doc("old", "model-old");
        String projectId = "project", mode = "", contentSuffix = "";
        long now, revision;
        int commands, reads;
        boolean available = true, advertised = true, noReads, readFailure, authoringUnavailable;
        EditorCommandResult.Status status = EditorCommandResult.Status.EXECUTED;
        Runnable onAvailable = () -> {}, beforeUi = () -> {}, duringCommand = () -> {}, afterCommand = () -> {};
        final CubismFacade facade = McpParameterStructureDomainTest.proxy(CubismFacade.class, (n, a) -> {
            guard();
            return switch (n) {
                case "activeProject" ->
                    projectId == null
                            ? Optional.empty()
                            : Optional.of(new ProjectSnapshot(
                                    projectId,
                                    "Project",
                                    Optional.of(java.nio.file.Path.of("should-not-appear")),
                                    docs,
                                    docs.stream()
                                            .map(d -> new ProjectContentSnapshot(
                                                    "content-" + d.documentId(),
                                                    "Content" + contentSuffix,
                                                    ProjectContentKind.MODEL,
                                                    Optional.of(java.nio.file.Path.of("should-not-appear")),
                                                    List.of(d.documentId())))
                                            .toList()));
                case "activeDocument" -> Optional.ofNullable(active);
                case "activeModel" -> active == null ? Optional.empty() : active.model();
                case "isHostPresent" -> active != null || projectId != null;
                default -> throw new AssertionError(n);
            };
        });
        final EditorCommandService service =
                McpParameterStructureDomainTest.proxy(EditorCommandService.class, (n, a) -> {
                    guard();
                    return switch (n) {
                        case "isAvailable" -> available;
                        case "available" -> {
                            onAvailable.run();
                            yield advertised ? Set.of(EditorCommand.NEW_MODEL) : Set.of();
                        }
                        case "execute" -> {
                            assertEquals(EditorCommand.NEW_MODEL, a[0]);
                            commands++;
                            duringCommand.run();
                            if (mode.equals("exception")) throw new IllegalStateException("secret failure");
                            if (mode.equals("failed"))
                                yield new EditorCommandResult(
                                        EditorCommandResult.Status.FAILED, EditorCommand.NEW_MODEL.id());
                            if (mode.equals("mismatch")) yield new EditorCommandResult(status, "save");
                            if (status == EditorCommandResult.Status.EXECUTED && !mode.equals("noop")) {
                                if (mode.equals("replace-old")) docs.clear();
                                if (mode.equals("replace-project")) projectId = "other";
                                if (projectId == null) projectId = "created-project";
                                active = doc(
                                        "created",
                                        mode.equals("image")
                                                ? null
                                                : mode.equals("reuse-model") ? "model-old" : "created-model");
                                docs.add(active);
                                if (mode.equals("two")) docs.add(doc("extra", "extra-model"));
                            }
                            afterCommand.run();
                            yield new EditorCommandResult(status, EditorCommand.NEW_MODEL.id());
                        }
                        default -> throw new AssertionError(n);
                    };
                });
        final McpExecutionBridge execution = new McpExecutionBridge(new UiScheduler() {
            public Registration runOnUiThread(Runnable r) {
                beforeUi.run();
                r.run();
                return () -> {};
            }

            public Registration runOnUiThreadLater(Runnable r, Duration delay) {
                throw new AssertionError();
            }
        });
        final McpToolCatalog tools =
                new McpDocumentLifecycleDomain(facade, service, execution, this::binding, () -> now).tools();

        Fixture() {
            docs.add(active);
        }

        void guard() {
            if (noReads) throw new AssertionError("result/replay read host");
            reads++;
            if (readFailure) throw new IllegalStateException("read failed");
        }

        Map<String, Object> binding() {
            guard();
            if (authoringUnavailable || active == null || active.model().isEmpty())
                throw new UnsupportedOperationException();
            return Map.of(
                    "documentId",
                    active.documentId(),
                    "modelId",
                    active.model().get().modelId(),
                    "historyGeneration",
                    1L,
                    "historyRevision",
                    revision,
                    "historyDocumentBinding",
                    "history-" + active.documentId(),
                    "historyManagerBinding",
                    "manager-" + active.documentId());
        }

        Map<String, Object> read() {
            return call(McpDocumentLifecycleDomain.READ, Map.of());
        }

        Map<String, Object> request(String id, Map<String, Object> read) {
            return Map.of("operationId", id, "snapshotId", read.get("snapshotId"));
        }

        Map<String, Object> create(Map<String, Object> request) {
            return call(McpDocumentLifecycleDomain.CREATE, request);
        }

        Map<String, Object> result(String id) {
            return call(McpDocumentLifecycleDomain.RESULT, Map.of("operationId", id));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> call(String name, Map<String, Object> args) {
            return McpRequestRegistry.inSession(
                    McpRequestRegistry.currentSessionId().isBlank() ? "test" : McpRequestRegistry.currentSessionId(),
                    () -> (Map<String, Object>) tools.call(name, args).get("structuredContent"));
        }
    }
}
