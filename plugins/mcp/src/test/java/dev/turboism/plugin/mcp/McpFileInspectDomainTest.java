package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.ui.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class McpFileInspectDomainTest {
    @Test
    void asyncInspectionReturnsKnownShaWithoutBytesOrStabilityClaim() {
        var f = new Fixture();
        var args = f.args("a", f.grant(UserFileMode.READ), 16);
        assertEquals("RUNNING", f.inspect(args).get("outcome"));
        f.complete("abc", false);
        assertEquals("RUNNING", f.result("a").get("outcome"));
        f.worker.runNext();
        var result = f.result("a");
        assertEquals("COMPLETE", result.get("outcome"));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result.get("sha256"));
        assertEquals(3L, result.get("byteLength"));
        assertEquals("UNPROVEN", result.get("fileStability"));
        assertEquals("UNVERIFIED", result.get("documentAssociation"));
        assertNotNull(result.get("completedAt"));
        assertFalse(result.containsKey("bytes"));
        assertFalse(result.toString().contains("secret"));
        assertEquals(1, f.handle.closes);
        assertEquals(result, f.inspect(args));
        assertEquals(1, f.reads);
    }

    @Test
    void prefixIsNeverACompleteFileHashAndEmptyFilesHaveRealHash() {
        var f = new Fixture();
        f.inspect(f.args("truncated", f.grant(UserFileMode.READ), 3));
        f.complete("abc", true);
        f.worker.runNext();
        assertEquals("TRUNCATED", f.result("truncated").get("outcome"));
        assertNull(f.result("truncated").get("sha256"));
        assertEquals(false, f.result("truncated").get("ok"));
        f.inspect(f.args("empty", f.grant(UserFileMode.READ), 3));
        f.complete("", false);
        f.worker.runNext();
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                f.result("empty").get("sha256"));
        assertEquals(0L, f.result("empty").get("byteLength"));
    }

    @Test
    void oversizedSdkResultRejectsWithoutHash() {
        var f = new Fixture();
        f.inspect(f.args("budget", f.grant(UserFileMode.READ), 1));
        f.complete("oversized", false);
        f.worker.runNext();
        assertEquals("SDK_BUDGET_VIOLATION", f.result("budget").get("code"));
        assertNull(f.result("budget").get("sha256"));
        assertEquals(1, f.handle.closes);
    }

    @Test
    void singleReadSlotAndIdentityConflictsPreventDuplicateIo() {
        var f = new Fixture();
        var args = f.args("a", f.grant(UserFileMode.READ), 16);
        assertEquals("RUNNING", f.inspect(args).get("outcome"));
        assertEquals("RUNNING", f.inspect(args).get("outcome"));
        var changed = new LinkedHashMap<>(args);
        changed.put("maxBytes", 17);
        assertEquals("OPERATION_ID_CONFLICT", f.inspect(changed).get("error"));
        assertEquals("READ_BUSY", f.inspect(f.args("b", "unused", 16)).get("error"));
        f.complete("abc", false);
        assertEquals("READ_BUSY", f.inspect(f.args("b", "unused", 16)).get("error"));
        f.worker.runNext();
        assertEquals(1, f.reads);
    }

    @Test
    void writeForeignExpiredAndSpentGrantsNeverRead() {
        var f = new Fixture();
        assertEquals(
                "FAILED",
                f.inspect(f.args("write", f.grant(UserFileMode.WRITE), 16)).get("outcome"));
        assertEquals(0, f.reads);
        var read = f.grant(UserFileMode.READ);
        f.session = "foreign";
        assertEquals("FAILED", f.inspect(f.args("foreign", read, 16)).get("outcome"));
        f.session = "session";
        f.grants.now = f.grants.now.plus(McpFileGrantDomain.TTL);
        assertEquals("FAILED", f.inspect(f.args("expired", read, 16)).get("outcome"));
        read = f.grant(UserFileMode.READ);
        f.inspect(f.args("valid", read, 16));
        f.complete("abc", false);
        f.worker.runNext();
        assertEquals("FAILED", f.inspect(f.args("spent", read, 16)).get("outcome"));
        assertEquals(1, f.reads);
    }

    @Test
    void sdkErrorsAndExceptionTextAreSanitized() {
        var f = new Fixture();
        for (var error : List.of(
                UserFileErrorCode.PERMISSION_DENIED,
                UserFileErrorCode.RUNTIME_UNAVAILABLE,
                UserFileErrorCode.IO_FAILURE,
                UserFileErrorCode.GRANT_REVOKED)) {
            String op = error.name();
            f.inspect(f.args(op, f.grant(UserFileMode.READ), 16));
            f.read.complete(new UserFileReadResult<>(
                    Optional.empty(), Optional.of(new UserFileError(error, "secret /path")), false));
            f.worker.runNext();
            assertEquals(error.name(), f.result(op).get("code"));
            assertFalse(f.result(op).toString().contains("secret"));
            assertEquals(1, f.handle.closes);
        }
        f.inspect(f.args("exception", f.grant(UserFileMode.READ), 16));
        f.read.completeExceptionally(new IllegalStateException("secret /path"));
        f.worker.runNext();
        assertEquals("READ_FAILED", f.result("exception").get("code"));
    }

    @Test
    void throwingOrNullSdkStageReleasesConsumedHandle() {
        for (boolean nullStage : List.of(false, true)) {
            var f = new Fixture();
            f.nullStage = nullStage;
            f.throwRead = !nullStage;
            var output = f.inspect(f.args("failure", f.grant(UserFileMode.READ), 16));
            assertEquals("FAILED", output.get("outcome"));
            assertEquals(1, f.handle.closes);
            assertFalse(output.toString().contains("secret"));
        }
    }

    @Test
    void closeDoesNotCancelNativeReadButDisposesLateResult() {
        var f = new Fixture();
        f.inspect(f.args("late", f.grant(UserFileMode.READ), 16));
        f.domain.close();
        f.domain.close();
        assertFalse(f.read.isCancelled());
        assertEquals(0, f.handle.closes);
        f.complete("secret", false);
        assertEquals(1, f.handle.closes);
        assertEquals("UNAVAILABLE", f.result("late").get("error"));
        assertEquals(0, f.worker.tasks.size());
    }

    @Test
    void sessionCloseDiscardsResultAndRetainsIoSlotUntilNativeCompletion() {
        var f = new Fixture();
        f.inspect(f.args("late", f.grant(UserFileMode.READ), 16));
        f.domain.dropSession("session");
        f.grants.domain.dropSession("session");
        f.session = "new";
        assertEquals("READ_BUSY", f.inspect(f.args("other", "unused", 16)).get("error"));
        f.complete("secret", false);
        assertEquals(1, f.handle.closes);
        f.session = "session";
        assertEquals("NOT_FOUND", f.result("late").get("error"));
        assertEquals(0, f.worker.tasks.size());
    }

    @Test
    void queuedHashCleanupSurvivesWorkerShutdown() {
        var f = new Fixture();
        f.inspect(f.args("queued", f.grant(UserFileMode.READ), 16));
        f.complete("secret", false);
        f.domain.close();
        assertTrue(f.worker.isShutdown());
        assertEquals(0, f.handle.closes);
        f.worker.runNext();
        assertEquals(1, f.handle.closes);
    }

    @Test
    void workerRejectionReleasesLeaseAndReadSlot() {
        var f = new Fixture();
        f.inspect(f.args("rejected", f.grant(UserFileMode.READ), 16));
        f.worker.reject = true;
        f.complete("secret", false);
        assertEquals("HASH_WORKER_UNAVAILABLE", f.result("rejected").get("code"));
        assertEquals(1, f.handle.closes);
        assertEquals("FAILED", f.inspect(f.args("not-busy", "missing", 16)).get("outcome"));
    }

    @Test
    void boundedRetentionNeverEvictsToPermitReplay() {
        var f = new Fixture();
        var first = f.args("first", f.grant(UserFileMode.READ), 16);
        f.inspect(first);
        f.complete("abc", false);
        f.worker.runNext();
        for (int i = 1; i < McpFileInspectDomain.MAX_OPERATIONS; i++) f.inspect(f.args("op" + i, "missing", 16));
        assertEquals(
                "RESULT_STORE_FULL", f.inspect(f.args("full", "missing", 16)).get("error"));
        assertEquals("COMPLETE", f.inspect(first).get("outcome"));
        assertEquals(1, f.reads);
    }

    @Test
    void schemaRejectsPathsByteBudgetsAndUnknownFields() {
        var f = new Fixture();
        var args = f.args("invalid", "unused", 16);
        var path = new LinkedHashMap<>(args);
        path.put("path", "/secret");
        assertThrows(IllegalArgumentException.class, () -> f.inspect(path));
        for (Object size : List.of(0, -1, McpFileInspectDomain.MAX_BYTES + 1, 1.5, "16")) {
            var invalid = new LinkedHashMap<>(args);
            invalid.put("maxBytes", size);
            assertThrows(IllegalArgumentException.class, () -> f.inspect(invalid));
        }
        f.session = "";
        assertEquals("SESSION_REQUIRED", f.inspect(args).get("error"));
        assertEquals(0, f.reads);
    }

    @Test
    void defaultSdkFallbackAboveSixteenMiBRemainsUnavailable() {
        for (boolean large : List.of(false, true)) {
            var f = new Fixture();
            var service = (UserFileAccessService) Proxy.newProxyInstance(
                    UserFileAccessService.class.getClassLoader(),
                    new Class<?>[] {UserFileAccessService.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("readBytesBounded"))
                            return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                        if (method.getName().equals("readBytes"))
                            return CompletableFuture.completedFuture(new UserFileReadResult<byte[]>(
                                    Optional.of(new byte[] {1}), Optional.empty(), false));
                        throw new AssertionError(method.getName());
                    });
            var domain = new McpFileInspectDomain(service, f.grants.domain, f.grants.execution, f.worker);
            var tools = domain.tools();
            int limit = 16 * 1024 * 1024 + (large ? 1 : 0);
            McpRequestRegistry.inSession(
                    "session",
                    () -> tools.call(
                            McpFileInspectDomain.INSPECT, f.args("fallback", f.grant(UserFileMode.READ), limit)));
            f.worker.runNext();
            var response = McpRequestRegistry.inSession(
                    "session", () -> tools.call(McpFileInspectDomain.RESULT, Map.of("operationId", "fallback")));
            var output = (Map<?, ?>) response.get("structuredContent");
            assertEquals(large ? "FAILED" : "COMPLETE", output.get("outcome"));
            if (large) assertEquals("RUNTIME_UNAVAILABLE", output.get("code"));
            assertEquals(1, f.handle.closes);
            domain.close();
        }
    }

    static class Fixture {
        final McpFileGrantDomainTest.Fixture grants = new McpFileGrantDomainTest.Fixture();
        final Worker worker = new Worker();
        String session = "session";
        int reads, grantsIssued;
        boolean throwRead, nullStage;
        McpFileGrantDomainTest.Handle handle;
        CompletableFuture<UserFileReadResult<byte[]>> read;
        final UserFileAccessService files = (UserFileAccessService) Proxy.newProxyInstance(
                UserFileAccessService.class.getClassLoader(),
                new Class<?>[] {UserFileAccessService.class},
                (p, m, a) -> {
                    if (!m.getName().equals("readBytesBounded")) throw new AssertionError(m.getName());
                    assertEquals(UserFileMode.READ, ((UserFileHandle) a[0]).mode());
                    reads++;
                    if (throwRead) throw new IllegalStateException("secret /path");
                    if (nullStage) return null;
                    read = new CompletableFuture<>();
                    return read;
                });
        final McpFileInspectDomain domain = new McpFileInspectDomain(files, grants.domain, grants.execution, worker);
        final McpToolCatalog tools = domain.tools();

        String grant(UserFileMode mode) {
            String operation = "grant" + grantsIssued++;
            var args = new LinkedHashMap<>(grants.args(operation));
            args.put("mode", mode.name());
            grants.call(McpFileGrantDomain.REQUEST, args);
            handle = new McpFileGrantDomainTest.Handle();
            handle.mode = mode;
            grants.grant(handle);
            return (String) grants.status(operation).get("grantId");
        }

        Map<String, Object> args(String op, String grant, int limit) {
            return Map.of("operationId", op, "grantId", grant, "maxBytes", limit);
        }

        Map<String, Object> inspect(Map<String, Object> args) {
            return call(McpFileInspectDomain.INSPECT, args);
        }

        Map<String, Object> result(String op) {
            return call(McpFileInspectDomain.RESULT, Map.of("operationId", op));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> call(String name, Map<String, Object> args) {
            return (Map<String, Object>) McpRequestRegistry.inSession(session, () -> tools.call(name, args))
                    .get("structuredContent");
        }

        void complete(String value, boolean truncated) {
            read.complete(new UserFileReadResult<>(
                    Optional.of(value.getBytes(StandardCharsets.UTF_8)), Optional.empty(), truncated));
        }
    }

    static class Worker extends AbstractExecutorService {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        boolean shutdown, reject;

        public void execute(Runnable task) {
            if (reject || shutdown) throw new RejectedExecutionException();
            tasks.add(task);
        }

        void runNext() {
            tasks.remove().run();
        }

        public void shutdown() {
            shutdown = true;
        }

        public List<Runnable> shutdownNow() {
            throw new AssertionError("must drain completion cleanup");
        }

        public boolean isShutdown() {
            return shutdown;
        }

        public boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }

        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
    }
}
