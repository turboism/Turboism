package dev.turboism.plugin.mcp;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.model.ModelObjectService;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService;
import dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService;
import dev.turboism.sdk.cubism.service.query.ParameterQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Diagnostic-only regression: a failed MCP startup surfaces the last
 * deterministic stage, the original Throwable's top stack frame, and the
 * exact original Throwable as cause; cleanup failures are recorded as
 * suppressed without ever replacing the cause.
 */
final class McpStartupDiagnosticTest {

    private static final String TOKEN = "test-token-0123456789-abcdef";

    @TempDir
    Path temporaryDirectory;

    @Test
    void failedContextAccessorReportsStageTopFrameAndExactCause() {
        final ThrowingContext context = new ThrowingContext();

        final McpHttpServer.McpStartupFailure failure = assertThrows(
            McpHttpServer.McpStartupFailure.class,
            () -> McpHttpServer.start(context)
        );

        assertEquals("context.cubismRead()", failure.stage());
        assertTrue(failure.getMessage().contains("context.cubismRead()"));
        assertTrue(failure.getMessage().contains("ThrowingContext.cubismRead"));
        assertSame(context.captured, failure.getCause());
    }

    @Test
    void connectionPublicationFailureReleasesPortAndConnectionFile() throws Exception {
        final int port;
        try (ServerSocket probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress("127.0.0.1", 0));
            port = probe.getLocalPort();
        }
        final ThrowingLogger logger = new ThrowingLogger(false, false);
        final McpHttpServerIntegrationTest.FakeReadServices reads =
            new McpHttpServerIntegrationTest.FakeReadServices();

        final McpHttpServer.McpStartupFailure failure = assertThrows(
            McpHttpServer.McpStartupFailure.class,
            () -> McpHttpServer.start(dependencies(logger, reads, port))
        );

        assertEquals("connection-file publication", failure.stage());
        assertTrue(failure.getMessage().contains("connection-file publication"));
        assertTrue(failure.getMessage().contains("ThrowingLogger.record"));
        assertSame(logger.captured, failure.getCause());
        // No connection file remains after the failure cleanup
        assertFalse(Files.exists(temporaryDirectory.resolve("mcp-connection.json")));
        // Direct synchronous same-port rebind proves the loopback server and
        // transport were released (no sleep/retry loop)
        try (ServerSocket rebound = new ServerSocket(port, 0, InetAddress.getByName("127.0.0.1"))) {
            assertEquals(port, rebound.getLocalPort());
        }
        // Transport close ran and observed shutdown cleanup is visible
        assertTrue(logger.messages.contains("Turboism MCP server stopped"));
    }

    @Test
    void sameObjectCleanupFailureIsNotSelfSuppressedAndCauseIsPreserved() {
        final ThrowingLogger logger = new ThrowingLogger(true, false);
        final McpHttpServerIntegrationTest.FakeReadServices reads =
            new McpHttpServerIntegrationTest.FakeReadServices();

        final McpHttpServer.McpStartupFailure failure = assertThrows(
            McpHttpServer.McpStartupFailure.class,
            () -> McpHttpServer.start(dependencies(logger, reads, 0))
        );

        assertEquals("connection-file publication", failure.stage());
        // The close-path logger failure rethrows the same object; it must not
        // be self-suppressed and must not replace the original cause
        assertSame(logger.captured, failure.getCause());
        assertEquals(0, failure.getCause().getSuppressed().length);
        assertTrue(logger.messages.contains("Turboism MCP server stopped"));
    }

    @Test
    void distinctCleanupThrowableIsAddedAsSuppressedWithCauseIdentity() {
        final ThrowingLogger logger = new ThrowingLogger(true, true);
        final McpHttpServerIntegrationTest.FakeReadServices reads =
            new McpHttpServerIntegrationTest.FakeReadServices();

        final McpHttpServer.McpStartupFailure failure = assertThrows(
            McpHttpServer.McpStartupFailure.class,
            () -> McpHttpServer.start(dependencies(logger, reads, 0))
        );

        assertEquals("connection-file publication", failure.stage());
        // Startup throwable is the first logger failure and stays the cause
        assertSame(logger.firstThrown, failure.getCause());
        // The distinct close-path throwable is recorded as suppressed
        final Throwable[] suppressed = failure.getCause().getSuppressed();
        assertEquals(1, suppressed.length);
        assertSame(logger.lastThrown, suppressed[0]);
    }

    private McpHttpServer.Dependencies dependencies(
        final PluginLogger logger,
        final McpHttpServerIntegrationTest.FakeReadServices reads,
        final int port
    ) {
        return new McpHttpServer.Dependencies(
            logger,
            ModelObjectService.unavailable(),
            reads.parameters,
            reads.hierarchy,
            reads.selection,
            reads.read,
            reads.clipMasks,
            immediateUi(),
            temporaryDirectory,
            port,
            TOKEN,
            120
        );
    }

    private static UiScheduler immediateUi() {
        return new UiScheduler() {
            @Override public Registration runOnUiThread(final Runnable work) {
                work.run();
                return () -> { };
            }

            @Override public Registration runOnUiThreadLater(
                final Runnable work,
                final Duration delay
            ) {
                work.run();
                return () -> { };
            }
        };
    }

    /**
     * Logger that records every message and throws deterministic exceptions
     * created inside {@link #record(String)} so the original top stack frame
     * is this class. With {@code throwOnEveryMessage} the cleanup-path log
     * call fails too; {@code distinctPerMessage} creates a new Throwable per
     * call (cleanup identity differs from startup identity).
     */
    private static final class ThrowingLogger implements PluginLogger {
        private final boolean throwOnEveryMessage;
        private final boolean distinctPerMessage;
        private final List<String> messages = new ArrayList<>();
        private IllegalStateException captured;
        private IllegalStateException firstThrown;
        private IllegalStateException lastThrown;

        private ThrowingLogger(final boolean throwOnEveryMessage, final boolean distinctPerMessage) {
            this.throwOnEveryMessage = throwOnEveryMessage;
            this.distinctPerMessage = distinctPerMessage;
        }

        @Override public void debug(final String message) { record(message); }
        @Override public void info(final String message) { record(message); }
        @Override public void warn(final String message) { record(message); }
        @Override public void error(final String message) { record(message); }
        @Override public void error(final String message, final Throwable throwable) {
            record(message);
        }

        private void record(final String message) {
            messages.add(message);
            if (!throwOnEveryMessage && !message.contains("listening")) {
                return;
            }
            if (distinctPerMessage) {
                lastThrown = new IllegalStateException("deterministic logger failure");
                if (firstThrown == null) {
                    firstThrown = lastThrown;
                }
                throw lastThrown;
            }
            if (captured == null) {
                captured = new IllegalStateException("deterministic logger failure");
            }
            throw captured;
        }
    }

    private static final class ThrowingContext implements PluginContext {
        private IllegalStateException captured;

        @Override public PluginDescriptor descriptor() { return null; }
        @Override public PluginLogger logger() { return null; }
        @Override public PluginPaths paths() { return null; }
        @Override public CubismFacade cubism() { return null; }
        @Override public List<PluginPermission> permissions() { return List.of(); }
        @Override public EventBus eventBus() { return null; }
        @Override public ActionRegistry actions() { return null; }
        @Override public MenuRegistry menus() { return null; }
        @Override public UiScheduler uiScheduler() { return null; }
        @Override public DisposableScope disposableScope() { return null; }
        @Override public DiagnosticReport diagnostics() { return null; }

        @Override public ParameterQueryService parameterQuery() { return null; }
        @Override public SelectionQueryService selectionQuery() { return null; }
        @Override public ModelHierarchyQueryService modelHierarchyQuery() { return null; }
        @Override public CubismClipMaskService cubismClipMasks() { return null; }

        @Override public CubismReadCapabilityService cubismRead() {
            captured = new IllegalStateException("deterministic read failure");
            throw captured;
        }
    }
}
