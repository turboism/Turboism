package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.command.*;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.*;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class McpDocumentFileDomainTest {
    @Test
    void typedSaveConsumesWriteGrantOnceAndDistinguishesPersistence() {
        var f = new Fixture();
        var args = f.request("save", f.grant(UserFileMode.WRITE));
        var output = f.save(args);
        assertEquals("EXECUTED", output.get("outcome"), output.toString());
        assertEquals("NATIVE_SAVE_REPORTED", output.get("code"));
        assertEquals(false, output.get("persistenceVerified"));
        assertEquals(false, output.get("reopenVerified"));
        assertEquals(1, f.saves);
        assertEquals(1, f.handle.closes);
        assertEquals("CONSUMED", f.grants.status("grant0").get("state"));
        assertEquals(output, f.save(args));
        assertEquals(output, f.result("save"));
        assertEquals(1, f.saves);
        assertEquals(
                "NOT_APPLIED",
                f.save(f.request("other", (String) args.get("grantId"))).get("outcome"));
        assertEquals(1, f.saves);
    }

    @Test
    void readAndForeignGrantsRejectWithoutNativeWrite() {
        var f = new Fixture();
        assertEquals(
                "NOT_APPLIED",
                f.save(f.request("read", f.grant(UserFileMode.READ))).get("outcome"));
        assertEquals(0, f.saves);
        assertEquals(0, f.handle.closes);
        var id = f.grant(UserFileMode.WRITE);
        f.session = "other";
        assertEquals("NOT_APPLIED", f.save(f.request("foreign", id)).get("outcome"));
        assertEquals(0, f.saves);
    }

    @Test
    void staleDocumentModelHistoryRejectBeforeGrantConsumption() {
        for (String field : fstate().keySet()) {
            var f = new Fixture();
            var args = f.request("stale", f.grant(UserFileMode.WRITE));
            var changed = new LinkedHashMap<>(f.state);
            changed.put(field, field.startsWith("historyG") || field.equals("historyRevision") ? 9L : "other");
            f.state = changed;
            assertEquals("STALE_STATE", f.save(args).get("code"));
            assertEquals(0, f.saves);
            assertEquals(0, f.handle.closes);
        }
    }

    @Test
    void secondStateCheckRejectsRaceAfterConsumingGrantAndReleasesIt() {
        var f = new Fixture();
        var args = f.request("race", f.grant(UserFileMode.WRITE));
        f.changeAtRead = 2;
        assertEquals("STALE_STATE", f.save(args).get("code"));
        assertEquals(0, f.saves);
        assertEquals(1, f.handle.closes);
    }

    @Test
    void failedAndExceptionalNativeResultsRemainUnknownAndCannotReplay() {
        for (boolean throwsFailure : List.of(false, true)) {
            var f = new Fixture();
            f.status = EditorCommandResult.Status.FAILED;
            f.throwNative = throwsFailure;
            var args = f.request("lost", f.grant(UserFileMode.WRITE));
            var output = f.save(args);
            assertEquals("OUTCOME_UNKNOWN", output.get("outcome"));
            assertFalse(output.toString().contains("secret"));
            assertEquals(output, f.save(args));
            assertEquals(1, f.saves);
            assertEquals(1, f.handle.closes);
        }
    }

    @Test
    void explicitNativeRejectionsDoNotClaimSave() {
        for (var status : List.of(
                EditorCommandResult.Status.UNAVAILABLE,
                EditorCommandResult.Status.REJECTED,
                EditorCommandResult.Status.PERMISSION_DENIED,
                EditorCommandResult.Status.INVALID_STATE,
                EditorCommandResult.Status.UNSUPPORTED_VERSION)) {
            var f = new Fixture();
            f.status = status;
            var result = f.save(f.request("reject", f.grant(UserFileMode.WRITE)));
            assertEquals("NOT_APPLIED", result.get("outcome"));
            assertEquals(status.name(), result.get("nativeStatus"));
            assertEquals(1, f.handle.closes);
        }
    }

    @Test
    void nativeSuccessWithChangedOrMissingStateReportsWarning() {
        for (boolean missing : List.of(false, true)) {
            var f = new Fixture();
            f.changeAtRead = 3;
            f.failState = missing;
            var output = f.save(f.request("warning", f.grant(UserFileMode.WRITE)));
            assertEquals("EXECUTED", output.get("outcome"));
            assertEquals("STATE_READBACK_WARNING", output.get("code"));
            assertEquals(false, output.get("persistenceVerified"));
        }
    }

    @Test
    void conflictingOperationAndFullResultStoreNeverWriteAgain() {
        var f = new Fixture();
        var args = f.request("first", f.grant(UserFileMode.WRITE));
        f.save(args);
        var conflict = new LinkedHashMap<>(args);
        conflict.put("grantId", "another");
        assertEquals("OPERATION_ID_CONFLICT", f.save(conflict).get("error"));
        for (int i = 1; i < McpDocumentFileDomain.MAX_OPERATIONS; i++) f.save(f.request("op" + i, "missing-grant"));
        assertEquals(
                "RESULT_STORE_FULL", f.save(f.request("full", "missing-grant")).get("error"));
        assertEquals("EXECUTED", f.save(args).get("outcome"));
        assertEquals(1, f.saves);
    }

    @Test
    void cancelBeforeUiAdmissionNeverConsumesGrant() {
        var f = new Fixture();
        f.cancelUi = true;
        var result = f.save(f.request("queued", f.grant(UserFileMode.WRITE)));
        assertEquals("NOT_APPLIED", result.get("outcome"));
        assertEquals("CANCELED_BEFORE_HOST", result.get("code"));
        assertEquals(0, f.saves);
        assertEquals(0, f.handle.closes);
    }

    @Test
    void unavailableServiceAndMissingStateDoNotConsumeGrant() {
        for (boolean noState : List.of(false, true)) {
            var f = new Fixture();
            var args = f.request("unavailable", f.grant(UserFileMode.WRITE));
            f.available = false;
            f.failState = noState;
            f.changeAtRead = noState ? 1 : 0;
            assertEquals("NOT_APPLIED", f.save(args).get("outcome"));
            assertEquals(0, f.saves);
            assertEquals(0, f.handle.closes);
        }
    }

    @Test
    void closedSchemaRejectsPathsOpenAndUnapprovedOverwrite() {
        var f = new Fixture();
        var args = f.request("schema", f.grant(UserFileMode.WRITE));
        for (String field : List.of("path", "command", "fileHandle")) {
            var modified = new LinkedHashMap<>(args);
            modified.put(field, "open");
            assertThrows(IllegalArgumentException.class, () -> f.save(modified));
        }
        var overwrite = new LinkedHashMap<>(args);
        overwrite.put("overwritePolicy", "REPLACE_EXISTING");
        assertThrows(IllegalArgumentException.class, () -> f.save(overwrite));
        assertEquals(0, f.saves);
    }

    @Test
    void runningResultsRemainDirectAndStateInputsAreImmutable() {
        var f = new Fixture();
        var args = f.request("running", f.grant(UserFileMode.WRITE));
        f.onNative = () -> {
            assertEquals("RUNNING", f.result("running").get("outcome"));
            assertEquals("RUNNING", f.save(args).get("outcome"));
        };
        assertEquals("EXECUTED", f.save(args).get("outcome"));
        assertEquals(1, f.saves);
        f.session = "foreign";
        assertEquals("OUTCOME_UNKNOWN", f.result("running").get("error"));
    }

    @Test
    void nativeCommandMismatchDoesNotMasqueradeAsSuccessfulSave() {
        var f = new Fixture();
        f.commandId = "open";
        var output = f.save(f.request("mismatch", f.grant(UserFileMode.WRITE)));
        assertEquals("OUTCOME_UNKNOWN", output.get("outcome"));
        assertEquals("RESULT_COMMAND_MISMATCH", output.get("code"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void queuedQueryIsDirectAndAdmittedStateDoesNotAliasCallerMap() {
        var f = new Fixture();
        var args = f.request("queued-read", f.grant(UserFileMode.WRITE));
        f.beforeUi = () -> {
            assertEquals("QUEUED", f.result("queued-read").get("outcome"));
            ((Map<String, Object>) args.get("expectedState")).put("documentId", "mutated-client-map");
        };
        assertEquals("EXECUTED", f.save(args).get("outcome"));
        assertEquals(1, f.saves);
    }

    @Test
    void saveRejectsGrantWhoseAuthorizedExtensionIsNotModelOnly() {
        var f = new Fixture();
        var args = new LinkedHashMap<>(f.grants.args("image"));
        args.put("mode", "WRITE");
        args.put("allowedExtensions", List.of("png", "cmo3"));
        f.grants.call(McpFileGrantDomain.REQUEST, args);
        var handle = new McpFileGrantDomainTest.Handle();
        handle.mode = UserFileMode.WRITE;
        f.grants.grant(handle);
        String grant = (String) f.grants.status("image").get("grantId");
        assertEquals("NOT_APPLIED", f.save(f.request("wrong-extension", grant)).get("outcome"));
        assertEquals(0, f.saves);
        assertEquals(0, handle.closes);
    }

    @Test
    void openConsumesReadGrantOnceAndNeverClaimsReadinessOrPersistence() {
        for (boolean empty : List.of(false, true)) {
            var f = new Fixture();
            if (empty) {
                f.inventory.docs.clear();
                f.inventory.active = null;
                f.inventory.projectId = null;
            }
            var args = f.openRequest("open", f.grant(UserFileMode.READ));
            var out = f.call(McpDocumentFileDomain.OPEN, args);
            assertEquals("EXECUTED", out.get("outcome"), out.toString());
            assertEquals("NATIVE_OPEN_DISPATCHED_REDISCOVER_DOCUMENTS", out.get("code"));
            assertEquals(false, out.get("reopenVerified"));
            assertEquals(false, out.get("persistenceVerified"));
            assertFalse(out.containsKey("authoringReady"));
            assertEquals(1, f.handle.closes);
            f.inventory.noReads = true;
            assertEquals(out, f.call(McpDocumentFileDomain.OPEN, args));
            assertEquals(out, f.result("open"));
            assertEquals(1, f.saves);
            assertEquals(
                    "OPERATION_ID_CONFLICT", f.save(f.request("open", "unused")).get("error"));
        }
    }

    @Test
    void emptyInventoryDoesNotBypassCommandServiceAvailabilityOrConsumeGrant() {
        var f = new Fixture();
        f.inventory.docs.clear();
        f.inventory.active = null;
        f.inventory.projectId = null;
        var args = f.openRequest("no-command-surface", f.grant(UserFileMode.READ));
        f.available = false;
        assertEquals("UNAVAILABLE", f.call(McpDocumentFileDomain.OPEN, args).get("code"));
        assertEquals(0, f.saves);
        assertEquals(0, f.handle.closes);
        assertEquals("GRANTED", f.grants.status("grant0").get("state"));
    }

    @Test
    void openRejectsWriteForeignAndStaleGrantsOrInventories() {
        for (String mode : List.of("write", "foreign", "stale", "expired", "race", "ttl-race")) {
            var f = new Fixture();
            var args = f.openRequest(mode, f.grant(mode.equals("write") ? UserFileMode.WRITE : UserFileMode.READ));
            switch (mode) {
                case "foreign" -> f.session = "foreign";
                case "stale" -> f.inventory.revision++;
                case "expired" -> f.inventory.now += McpDocumentLifecycleDomain.TTL;
                case "race" -> f.onAvailable = () -> f.inventory.revision++;
                case "ttl-race" -> f.onAvailable = () -> f.inventory.now += McpDocumentLifecycleDomain.TTL;
            }
            assertEquals("NOT_APPLIED", f.call(McpDocumentFileDomain.OPEN, args).get("outcome"), mode);
            assertEquals(0, f.saves);
            assertEquals(mode.endsWith("race") ? 1 : 0, f.handle.closes, mode);
        }
    }

    @Test
    void openUnknownAndCancelledRequestsAreNeverReplayed() {
        for (String mode : List.of("failed", "mismatch", "throw", "cancel")) {
            var f = new Fixture();
            var args = f.openRequest(mode, f.grant(UserFileMode.READ));
            switch (mode) {
                case "failed" -> f.status = EditorCommandResult.Status.FAILED;
                case "mismatch" -> f.commandId = "save.as";
                case "throw" -> f.throwNative = true;
                case "cancel" -> f.cancelUi = true;
            }
            var out = f.call(McpDocumentFileDomain.OPEN, args);
            assertEquals(mode.equals("cancel") ? "NOT_APPLIED" : "OUTCOME_UNKNOWN", out.get("outcome"));
            assertEquals(out, f.call(McpDocumentFileDomain.OPEN, args));
            assertEquals(mode.equals("cancel") ? 0 : 1, f.saves);
        }
    }

    @Test
    void openSchemaRejectsRawPathsAndSaveOptions() {
        var f = new Fixture();
        var args = f.openRequest("schema-open", "ungranted");
        for (String field : List.of("path", "fileHandle", "expectedState", "overwritePolicy")) {
            var bad = new LinkedHashMap<>(args);
            bad.put(field, "forbidden");
            assertThrows(IllegalArgumentException.class, () -> f.call(McpDocumentFileDomain.OPEN, bad));
        }
        assertEquals(0, f.saves);
    }

    static Map<String, Object> fstate() {
        return Map.of(
                "documentId",
                "doc",
                "modelId",
                "model",
                "historyGeneration",
                1L,
                "historyRevision",
                2L,
                "historyDocumentBinding",
                "hd",
                "historyManagerBinding",
                "hm");
    }

    static class Fixture {
        final McpFileGrantDomainTest.Fixture grants = new McpFileGrantDomainTest.Fixture();
        String session = "session", commandId = "save.as";
        EditorFileCommand command = EditorFileCommand.SAVE_AS;
        Runnable onAvailable = () -> {};
        final McpDocumentLifecycleDomainTest.Fixture inventory = new McpDocumentLifecycleDomainTest.Fixture();
        final McpDocumentLifecycleDomain documents = new McpDocumentLifecycleDomain(
                inventory.facade, inventory.service, inventory.execution, inventory::binding, () -> inventory.now);
        final McpToolCatalog documentTools = documents.tools();
        Map<String, Object> state = fstate();
        boolean available = true, throwNative, cancelUi, failState;
        int saves, reads, changeAtRead, grantCount;
        Runnable onNative = () -> {};
        Runnable beforeUi = () -> {};
        EditorCommandResult.Status status = EditorCommandResult.Status.EXECUTED;
        McpFileGrantDomainTest.Handle handle;
        final EditorCommandService commands = (EditorCommandService) Proxy.newProxyInstance(
                EditorCommandService.class.getClassLoader(), new Class<?>[] {EditorCommandService.class}, (p, m, a) -> {
                    if (m.getName().equals("isAvailable")) {
                        onAvailable.run();
                        return available;
                    }
                    if (!m.getName().equals("execute") || !(a[0] instanceof EditorFileCommandRequest request))
                        throw new AssertionError(m.getName());
                    assertEquals(command, request.command());
                    assertEquals(
                            command == EditorFileCommand.OPEN ? UserFileMode.READ : UserFileMode.WRITE,
                            request.file().mode());
                    assertEquals(EditorOverwritePolicy.REJECT_EXISTING, request.overwritePolicy());
                    saves++;
                    onNative.run();
                    if (throwNative) throw new IllegalStateException("secret path");
                    return new EditorCommandResult(status, commandId);
                });
        final McpExecutionBridge execution = new McpExecutionBridge(new UiScheduler() {
            public Registration runOnUiThread(Runnable work) {
                if (cancelUi) throw new CancellationException();
                beforeUi.run();
                work.run();
                return () -> {};
            }

            public Registration runOnUiThreadLater(Runnable work, Duration delay) {
                throw new AssertionError();
            }
        });
        final McpToolCatalog tools = new McpDocumentFileDomain(
                        commands,
                        grants.domain,
                        execution,
                        () -> {
                            reads++;
                            if (changeAtRead > 0 && reads >= changeAtRead) {
                                if (failState) throw new IllegalStateException("secret");
                                state = new LinkedHashMap<>(state);
                                state.put("historyRevision", 9L);
                            }
                            return state;
                        },
                        documents)
                .tools();

        String grant(UserFileMode mode) {
            var args = new LinkedHashMap<>(grants.args("grant" + grantCount++));
            args.put("mode", mode.name());
            args.put("allowedExtensions", List.of("cmo3"));
            var output = grants.call(McpFileGrantDomain.REQUEST, args);
            assertEquals("PENDING", output.get("state"));
            handle = new McpFileGrantDomainTest.Handle();
            handle.mode = mode;
            grants.grant(handle);
            return (String) grants.status((String) args.get("operationId")).get("grantId");
        }

        Map<String, Object> openRequest(String op, String grant) {
            command = EditorFileCommand.OPEN;
            commandId = "open";
            var read = McpRequestRegistry.inSession(session, () -> (Map<?, ?>) documentTools
                    .call(McpDocumentLifecycleDomain.READ, Map.of())
                    .get("structuredContent"));
            return Map.of("operationId", op, "grantId", grant, "documentSnapshotId", read.get("snapshotId"));
        }

        Map<String, Object> request(String op, String grant) {
            return Map.of(
                    "operationId",
                    op,
                    "grantId",
                    grant,
                    "overwritePolicy",
                    "REJECT_EXISTING",
                    "expectedState",
                    new LinkedHashMap<>(state));
        }

        Map<String, Object> save(Map<String, Object> args) {
            return call(McpDocumentFileDomain.SAVE_AS, args);
        }

        Map<String, Object> result(String op) {
            return call(McpDocumentFileDomain.RESULT, Map.of("operationId", op));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> call(String name, Map<String, Object> args) {
            return (Map<String, Object>) McpRequestRegistry.inSession(session, () -> tools.call(name, args))
                    .get("structuredContent");
        }
    }
}
