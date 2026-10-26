package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.*;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class McpFileGrantDomainTest {
    @Test
    void asyncGrantIsOpaqueAndExactReplayNeverOpensAnotherChooser() {
        var f = new Fixture();
        assertEquals("PENDING", f.request("a").get("state"));
        assertEquals("PENDING", f.request("a").get("state"));
        assertEquals(1, f.calls);
        var handle = new Handle();
        f.grant(handle);
        var result = f.status("a");
        assertEquals("GRANTED", result.get("state"));
        assertNotNull(result.get("grantId"));
        assertNotEquals(handle.id(), result.get("grantId"));
        assertEquals(result, f.request("a"));
        assertEquals(false, result.get("nativeChooserPending"));
        assertFalse(result.toString().contains("secret"));
        assertEquals(1, f.calls);
    }

    @Test
    void nativeGrantMustHaveMatchingModeLifetimeAndActiveState() {
        for (String invalid : List.of("mode", "lifetime", "state")) {
            var f = new Fixture();
            f.request("a");
            var h = new Handle();
            if (invalid.equals("mode")) h.mode = UserFileMode.WRITE;
            if (invalid.equals("lifetime")) h.lifetime = UserFileLifetime.ONE_OPERATION;
            if (invalid.equals("state")) h.state = UserFileHandleState.CLOSED;
            f.grant(h);
            assertEquals("FAILED", f.status("a").get("state"));
            assertEquals(1, h.closes);
        }
    }

    @Test
    void conflictBusyAndCrossSessionDoNotCreateChoosers() {
        var f = new Fixture();
        f.request("a");
        var changed = new LinkedHashMap<>(f.args("a"));
        changed.put("mode", "WRITE");
        assertEquals(
                "OPERATION_ID_CONFLICT",
                f.call(McpFileGrantDomain.REQUEST, changed).get("error"));
        assertEquals("CHOOSER_BUSY", f.request("b").get("error"));
        f.session = "other";
        assertEquals("NOT_FOUND", f.status("a").get("error"));
        assertEquals("NOT_FOUND", f.revoke("a").get("error"));
        assertEquals("CHOOSER_BUSY", f.request("a").get("error"));
        assertEquals(1, f.calls);
    }

    @Test
    void revokePendingDoesNotClaimDialogCancellationAndClosesLateGrant() {
        var f = new Fixture();
        f.request("a");
        var revoked = f.revoke("a");
        assertEquals("REVOKED", revoked.get("state"));
        assertEquals(true, revoked.get("nativeChooserPending"));
        assertEquals(false, revoked.get("hostCancellationConfirmed"));
        assertEquals("CHOOSER_BUSY", f.request("b").get("error"));
        var h = new Handle();
        f.grant(h);
        assertEquals(1, h.revokes);
        assertEquals(1, h.closes);
        assertEquals("REVOKED", f.request("a").get("state"));
        assertEquals("PENDING", f.request("b").get("state"));
        assertEquals(2, f.calls);
    }

    @Test
    void activeRevocationIsIdempotentAndDropsCapability() {
        var f = new Fixture();
        f.request("a");
        var h = new Handle();
        f.grant(h);
        var revoked = f.revoke("a");
        assertNull(revoked.get("grantId"));
        assertEquals(revoked, f.revoke("a"));
        assertEquals(1, h.closes);
        assertEquals(1, h.revokes);
    }

    @Test
    void expiryReleasesActiveAndLateHandlesWithoutRenewal() {
        for (boolean late : List.of(false, true)) {
            var f = new Fixture();
            var initial = f.request("a");
            var h = new Handle();
            if (!late) f.grant(h);
            f.now = f.now.plus(McpFileGrantDomain.TTL);
            f.domain.expire();
            assertEquals("EXPIRED", f.status("a").get("state"));
            assertEquals(initial.get("expiresAt"), f.request("a").get("expiresAt"));
            if (late) f.grant(h);
            assertEquals(1, h.closes);
            assertEquals(1, f.calls);
        }
    }

    @Test
    void sessionCloseRemovesActiveAndDiscardsLateGrant() {
        for (boolean late : List.of(false, true)) {
            var f = new Fixture();
            f.request("a");
            var h = new Handle();
            if (!late) f.grant(h);
            f.domain.dropSession("session");
            assertEquals("NOT_FOUND", f.status("a").get("error"));
            if (late) f.grant(h);
            assertEquals(1, h.closes);
        }
    }

    @Test
    void serverCloseReleasesHandlesAndRejectsNewRequests() {
        for (boolean late : List.of(false, true)) {
            var f = new Fixture();
            f.request("a");
            var h = new Handle();
            if (!late) f.grant(h);
            f.domain.close();
            f.domain.close();
            if (late) f.grant(h);
            assertEquals(1, h.closes);
            assertEquals("UNAVAILABLE", f.request("b").get("error"));
        }
    }

    @Test
    void nativeRevocationAndSpentHandleAreObserved() {
        for (var state : List.of(UserFileHandleState.REVOKED, UserFileHandleState.CLOSED)) {
            var f = new Fixture();
            f.request("a");
            var h = new Handle();
            f.grant(h);
            h.state = state;
            assertEquals(state.name(), f.status("a").get("state"));
            assertNull(f.status("a").get("grantId"));
        }
    }

    @Test
    void unavailableDeniedCanceledAndExceptionalResultsAreSanitized() {
        var f = new Fixture();
        f.available = false;
        assertEquals("UNAVAILABLE", f.request("a").get("error"));
        assertEquals(0, f.calls);
        f.available = true;
        f.request("a");
        f.stage.complete(new UserFileRequestResult(
                UserFileRequestStatus.DENIED,
                Optional.empty(),
                Optional.of(new UserFileError(UserFileErrorCode.PERMISSION_DENIED, "/secret/path token"))));
        assertEquals("DENIED", f.status("a").get("state"));
        assertFalse(f.status("a").toString().contains("secret"));
        f.request("b");
        f.stage.complete(new UserFileRequestResult(UserFileRequestStatus.CANCELED, Optional.empty(), Optional.empty()));
        assertEquals("CANCELED", f.status("b").get("state"));
        f.request("c");
        f.stage.completeExceptionally(new IllegalStateException("/secret/path token"));
        assertEquals("FAILED", f.status("c").get("state"));
        assertFalse(f.status("c").toString().contains("secret"));
    }

    @Test
    void boundedIdentityStoreDoesNotEvictToPermitReplay() {
        var f = new Fixture();
        for (int i = 0; i < McpFileGrantDomain.MAX_REQUESTS; i++) {
            f.request("op" + i);
            f.stage.complete(
                    new UserFileRequestResult(UserFileRequestStatus.CANCELED, Optional.empty(), Optional.empty()));
        }
        assertEquals("RESULT_STORE_FULL", f.request("overflow").get("error"));
        assertEquals("CANCELED", f.request("op0").get("state"));
        assertEquals(McpFileGrantDomain.MAX_REQUESTS, f.calls);
    }

    @Test
    void schemaAndNativeContractRejectPathsAndInvalidFiltersBeforeOpeningUi() {
        var f = new Fixture();
        var path = new LinkedHashMap<>(f.args("a"));
        path.put("path", "/secret/path");
        assertThrows(IllegalArgumentException.class, () -> f.call(McpFileGrantDomain.REQUEST, path));
        for (String ext : List.of("../cmo3", "*.psd", "a/b")) {
            var args = new LinkedHashMap<>(f.args("a"));
            args.put("allowedExtensions", List.of(ext));
            assertEquals(
                    "INVALID_ARGUMENT", f.call(McpFileGrantDomain.REQUEST, args).get("error"));
        }
        assertEquals(0, f.calls);
        f.session = "";
        assertEquals("SESSION_REQUIRED", f.request("a").get("error"));
    }

    @Test
    void syncCompletionAndThrownRequestDoNotLeaveChooserBusy() {
        var f = new Fixture();
        f.throwRequest = true;
        assertEquals("FAILED", f.request("a").get("state"));
        f.throwRequest = false;
        f.immediate = true;
        assertEquals("CANCELED", f.request("b").get("state"));
        assertEquals("CANCELED", f.request("c").get("state"));
    }

    @Test
    void concurrentReplayOpensOnlyOneChooser() throws Exception {
        var f = new Fixture();
        var pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Callable<Map<String, Object>>>();
            for (int i = 0; i < 16; i++) tasks.add(() -> f.request("a"));
            for (var result : pool.invokeAll(tasks))
                assertEquals("PENDING", result.get().get("state"));
            assertEquals(1, f.calls);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cleanupAttemptsCloseEvenIfNativeRevokeThrows() {
        var f = new Fixture();
        f.request("a");
        var h = new Handle();
        h.throwRevoke = true;
        f.grant(h);
        assertEquals("REVOKED", f.revoke("a").get("state"));
        assertEquals(1, h.closes);
        assertNull(f.status("a").get("grantId"));
    }

    @Test
    void consumedLeaseOwnsHandleUntilNativeWorkEnds() {
        var f = new Fixture();
        f.request("a");
        var handle = new Handle();
        f.grant(handle);
        String id = (String) f.status("a").get("grantId");
        assertThrows(IllegalArgumentException.class, () -> f.domain.consume("session", id, UserFileMode.WRITE));
        assertThrows(IllegalStateException.class, () -> f.domain.consume("foreign", id, UserFileMode.READ));
        var lease = f.domain.consume("session", id, UserFileMode.READ);
        assertEquals("CONSUMED", f.status("a").get("state"));
        assertThrows(IllegalStateException.class, () -> f.domain.consume("session", id, UserFileMode.READ));
        f.now = f.now.plus(McpFileGrantDomain.TTL);
        f.domain.expire();
        f.revoke("a");
        f.domain.dropSession("session");
        f.domain.close();
        assertEquals(0, handle.closes);
        assertSame(handle, lease.handle());
        lease.close();
        lease.close();
        assertEquals(1, handle.closes);
        assertThrows(IllegalStateException.class, lease::handle);
    }

    @Test
    void expiredGrantCannotBeConsumed() {
        var f = new Fixture();
        f.request("a");
        var handle = new Handle();
        f.grant(handle);
        String id = (String) f.status("a").get("grantId");
        f.now = f.now.plus(McpFileGrantDomain.TTL);
        assertThrows(IllegalStateException.class, () -> f.domain.consume("session", id, UserFileMode.READ));
        assertEquals(1, handle.closes);
    }

    static class Handle implements UserFileHandle {
        UserFileMode mode = UserFileMode.READ;
        UserFileLifetime lifetime = UserFileLifetime.UNTIL_DISABLE;
        UserFileHandleState state = UserFileHandleState.ACTIVE;
        int closes, revokes;
        boolean throwRevoke;

        public String id() {
            return "secret-native-id";
        }

        public String displayName() {
            throw new AssertionError("do not expose filenames or paths");
        }

        public UserFileMode mode() {
            return mode;
        }

        public UserFileLifetime lifetime() {
            return lifetime;
        }

        public UserFileHandleState state() {
            return state;
        }

        public void revoke() {
            revokes++;
            if (throwRevoke) throw new IllegalStateException("secret");
            state = UserFileHandleState.REVOKED;
        }

        public void close() {
            closes++;
            state = UserFileHandleState.CLOSED;
        }
    }

    static class Fixture {
        Instant now = Instant.parse("2026-10-24T00:00:00Z");
        String session = "session";
        boolean available = true, throwRequest, immediate;
        int calls;
        CompletableFuture<UserFileRequestResult> stage;
        final UserFileAccessService service = (UserFileAccessService) Proxy.newProxyInstance(
                UserFileAccessService.class.getClassLoader(),
                new Class<?>[] {UserFileAccessService.class},
                (p, m, a) -> {
                    if (m.getName().equals("isAvailable")) return available;
                    if (!m.getName().equals("request")) throw new AssertionError(m.getName());
                    calls++;
                    if (throwRequest) throw new IllegalStateException("secret");
                    var request = (UserFileRequest) a[0];
                    assertEquals(UserFileLifetime.UNTIL_DISABLE, request.lifetime());
                    stage = new CompletableFuture<>();
                    if (immediate)
                        stage.complete(new UserFileRequestResult(
                                UserFileRequestStatus.CANCELED, Optional.empty(), Optional.empty()));
                    return stage;
                });
        final McpExecutionBridge execution = new McpExecutionBridge(new UiScheduler() {
            public Registration runOnUiThread(Runnable r) {
                throw new AssertionError("SDK owns chooser scheduling");
            }

            public Registration runOnUiThreadLater(Runnable r, Duration delay) {
                throw new AssertionError();
            }
        });
        final McpFileGrantDomain domain = new McpFileGrantDomain(service, execution, new Clock() {
            public Instant instant() {
                return now;
            }

            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            public Clock withZone(ZoneId zone) {
                return this;
            }
        });
        final McpToolCatalog tools = domain.tools();

        Map<String, Object> args(String op) {
            return Map.of(
                    "operationId",
                    op,
                    "purpose",
                    "Open modeling test copy",
                    "mode",
                    "READ",
                    "allowedExtensions",
                    List.of("cmo3", "psd"));
        }

        Map<String, Object> request(String op) {
            return call(McpFileGrantDomain.REQUEST, args(op));
        }

        Map<String, Object> status(String op) {
            return call(McpFileGrantDomain.STATUS, Map.of("operationId", op));
        }

        Map<String, Object> revoke(String op) {
            return call(McpFileGrantDomain.REVOKE, Map.of("operationId", op));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> call(String name, Map<String, Object> args) {
            return (Map<String, Object>) McpRequestRegistry.inSession(session, () -> tools.call(name, args))
                    .get("structuredContent");
        }

        void grant(Handle handle) {
            stage.complete(
                    new UserFileRequestResult(UserFileRequestStatus.GRANTED, Optional.of(handle), Optional.empty()));
        }
    }
}
