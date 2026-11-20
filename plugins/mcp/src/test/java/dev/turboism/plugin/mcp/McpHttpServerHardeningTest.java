package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.json.Json;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.test.ui.FakeDirectUiScheduler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Transport contract for the loopback MCP server: host-header admission, lane admission for
 * direct and host-bound tools, session cleanup, and rate-limit responses.
 */
final class McpHttpServerHardeningTest {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final long AWAIT_TIMEOUT_MILLIS = 10_000L;

    @TempDir
    Path stateDir;

    @Test
    void hostHeaderWhitelistAcceptsLoopbackAndRejectsForeign() {
        assertTrue(McpHttpServer.hostAllowed("127.0.0.1"));
        assertTrue(McpHttpServer.hostAllowed("127.0.0.1:43123"));
        assertTrue(McpHttpServer.hostAllowed("localhost"));
        assertTrue(McpHttpServer.hostAllowed("LOCALHOST:43123"));
        assertTrue(McpHttpServer.hostAllowed("[::1]"));
        assertTrue(McpHttpServer.hostAllowed("[::1]:43123"));
        assertFalse(McpHttpServer.hostAllowed(null));
        assertFalse(McpHttpServer.hostAllowed(""));
        assertFalse(McpHttpServer.hostAllowed("evil.example.com"));
        assertFalse(McpHttpServer.hostAllowed("127.0.0.1.evil.com"));
        assertFalse(McpHttpServer.hostAllowed("localhost:abc"));
        assertFalse(McpHttpServer.hostAllowed("[::2]"));
        assertFalse(McpHttpServer.hostAllowed("::1"));
    }

    /**
     * Owner-only permissions exist only on a POSIX filesystem. The publish contract itself
     * always applies, so this test asserts the payload everywhere and the mode bits only where
     * the filesystem can express them.
     */
    @Test
    void publishRoundTripsContentAndStaysSilentWhereOwnerOnlyModesExist(@TempDir final Path stateDir) throws Exception {
        final Path target = stateDir.resolve("mcp-connection.json");
        final List<String> warnings = new ArrayList<>();
        McpStateFiles.publish(target, ".mcp-test-", "payload".getBytes(StandardCharsets.UTF_8), warnings::add);
        assertEquals("payload", Files.readString(target, StandardCharsets.UTF_8));
        assertTrue(warnings.isEmpty(), () -> String.join(" | ", warnings));
        if (!Files.getFileStore(stateDir).supportsFileAttributeView("posix")) return;
        final var permissions = Files.getPosixFilePermissions(target);
        assertTrue(permissions.stream().allMatch(p -> p.name().startsWith("OWNER_")), () -> permissions.toString());
    }

    /**
     * A direct-affinity tool never waits on the host, so it must answer while every host-work
     * slot is occupied. A host-bound read must be rejected in the same state.
     */
    @Test
    void directToolsAnswerWhileEveryHostWorkSlotIsSaturated() throws Exception {
        final StalledUi host = new StalledUi();
        try (McpHttpServer server = start(host, 120)) {
            final String session = initializeSession(server.endpoint());
            final ExecutorService callers = Executors.newFixedThreadPool(4, daemonFactory());
            try {
                final List<Future<HttpResponse<byte[]>>> hostReads = new ArrayList<>();
                for (int index = 0; index < 4; index++) {
                    final int id = 700 + index;
                    hostReads.add(callers.submit(
                            () -> toolCall(server.endpoint(), session, id, McpDocumentLifecycleDomain.READ, Map.of())));
                }
                awaitSubmitted(host, server.hostWorkBound());

                final List<String> directTools = List.of(
                        McpCapabilitiesDomain.CAPABILITIES_READ,
                        McpPoseGridDomain.STATUS,
                        McpDocumentLifecycleDomain.RESULT);
                int id = 760;
                for (String tool : directTools) {
                    final HttpResponse<byte[]> response = toolCallWithin(
                            server.endpoint(),
                            session,
                            id++,
                            tool,
                            Map.of("operationId", "absent-" + tool),
                            Duration.ofSeconds(5));
                    assertEquals(200, response.statusCode(), tool + " must answer without a host-work slot");
                }

                final long rejections = hostReads.stream()
                        .map(McpHttpServerHardeningTest::quietly)
                        .filter(response -> response != null && response.statusCode() == 503)
                        .count();
                assertTrue(rejections >= 1, "the over-bound host read must fail explicitly, not queue");
            } finally {
                callers.shutdownNow();
            }
        }
    }

    /** Closing a session must release session-scoped domain retention for the next session. */
    @Test
    void deletingASessionReleasesEveryStatefulDomainRetention() throws Exception {
        try (McpHttpServer server = start(FakeDirectUiScheduler.direct(), 120)) {
            final String session = initializeSession(server.endpoint());
            final int cleanups = server.sessionCleanupCount();
            assertTrue(cleanups >= 12, "every stateful domain must register a cleanup: " + cleanups);

            final HttpResponse<byte[]> deleted = delete(server.endpoint(), session);
            assertEquals(200, deleted.statusCode());

            // A new session sees no retained result of the closed one.
            final String next = initializeSession(server.endpoint());
            final HttpResponse<byte[]> retained =
                    toolCall(server.endpoint(), next, 802, McpPoseGridDomain.RESULT, Map.of("operationId", "absent"));
            assertEquals(200, retained.statusCode());
            assertEquals("NOT_FOUND", object(structured(retained).get("error")).get("code"));
        }
    }

    /** Session close stays available after the request budget is exhausted. */
    @Test
    void sessionCloseBypassesAnExhaustedRateLimitWindow() throws Exception {
        final int perMinute = 10;
        try (McpHttpServer server = start(FakeDirectUiScheduler.direct(), perMinute)) {
            final String session = initializeSession(server.endpoint());
            HttpResponse<byte[]> limited = null;
            for (int index = 0; index < perMinute + 10 && limited == null; index++) {
                final HttpResponse<byte[]> response = toolCall(
                        server.endpoint(), session, 900 + index, McpCapabilitiesDomain.CAPABILITIES_READ, Map.of());
                if (response.statusCode() == 429) limited = response;
            }
            assertTrue(limited != null, "the window budget must reject an over-limit request");
            final String retryAfter =
                    limited.headers().firstValue("Retry-After").orElseThrow();
            final long seconds = Long.parseLong(retryAfter);
            assertTrue(seconds >= 1 && seconds <= 60, "Retry-After must advertise the exact wait: " + retryAfter);

            assertEquals(200, delete(server.endpoint(), session).statusCode());
        }
    }

    private McpHttpServer start(final UiScheduler host, final int requestsPerMinute) throws Exception {
        final McpHttpServerTestFixtures.FakeReadServices reads = new McpHttpServerTestFixtures.FakeReadServices();
        return McpHttpServer.start(new McpHttpServer.Dependencies(
                new McpHttpServerTestFixtures.CapturingLogger(),
                new McpHttpServerTestFixtures.MutableObjects(),
                reads.hierarchy,
                reads.selection,
                reads.read,
                reads.clipMasks,
                host,
                stateDir,
                0,
                requestsPerMinute));
    }

    private String initializeSession(final URI endpoint) throws Exception {
        final HttpResponse<byte[]> initialized = send(
                endpoint,
                null,
                null,
                Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        1,
                        "method",
                        "initialize",
                        "params",
                        Map.of(
                                "protocolVersion",
                                McpProtocol.VERSION,
                                "capabilities",
                                Map.of(),
                                "clientInfo",
                                Map.of("name", "hardening-test", "version", "1"))));
        assertEquals(200, initialized.statusCode());
        final String session =
                initialized.headers().firstValue("MCP-Session-Id").orElseThrow();
        assertEquals(
                202,
                send(endpoint, session, null, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"))
                        .statusCode());
        return session;
    }

    private HttpResponse<byte[]> toolCall(
            final URI endpoint, final String session, final int id, final String tool, final Map<String, Object> args)
            throws Exception {
        return toolCallWithin(endpoint, session, id, tool, args, REQUEST_TIMEOUT);
    }

    private HttpResponse<byte[]> toolCallWithin(
            final URI endpoint,
            final String session,
            final int id,
            final String tool,
            final Map<String, Object> args,
            final Duration timeout)
            throws Exception {
        return send(
                endpoint,
                session,
                timeout,
                Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        id,
                        "method",
                        "tools/call",
                        "params",
                        Map.of("name", tool, "arguments", args)));
    }

    private HttpResponse<byte[]> send(
            final URI endpoint, final String session, final Duration timeout, final Map<String, Object> body)
            throws Exception {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout == null ? REQUEST_TIMEOUT : timeout)
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(Json.bytes(body)));
        if (session != null) {
            builder.header("MCP-Session-Id", session).header("MCP-Protocol-Version", McpProtocol.VERSION);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> delete(final URI endpoint, final String session) throws Exception {
        final String token = Files.readString(stateDir.resolve(McpAccessToken.FILE_NAME), StandardCharsets.UTF_8)
                .strip();
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(endpoint)
                                .timeout(REQUEST_TIMEOUT)
                                .header("MCP-Session-Id", session)
                                .header("Authorization", "Bearer " + token)
                                .DELETE()
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
    }

    private static java.util.concurrent.ThreadFactory daemonFactory() {
        return task -> {
            final Thread thread = new Thread(task, "hardening-caller");
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void awaitSubmitted(final StalledUi host, final int expected) {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_TIMEOUT_MILLIS);
        while (host.submitted() < expected) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("host work did not start: " + host.submitted());
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
    }

    private static HttpResponse<byte[]> quietly(final Future<HttpResponse<byte[]>> pending) {
        try {
            return pending.get(30, TimeUnit.SECONDS);
        } catch (Exception pendingResult) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(final Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> structured(final HttpResponse<byte[]> response) {
        final Map<String, Object> envelope = object(Json.parseObject(response.body()));
        return object(object(envelope.get("result")).get("structuredContent"));
    }

    /** Records host work and never runs it, standing in for a busy host thread. */
    private static final class StalledUi implements UiScheduler {
        private final AtomicInteger submitted = new AtomicInteger();

        int submitted() {
            return submitted.get();
        }

        @Override
        public Registration runOnUiThread(final Runnable work) {
            submitted.incrementAndGet();
            return () -> {};
        }

        @Override
        public Registration runOnUiThreadLater(final Runnable work, final Duration delay) {
            return runOnUiThread(work);
        }
    }
}
