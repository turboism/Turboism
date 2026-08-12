package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService;
import dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService;
import dev.turboism.sdk.cubism.service.query.ParameterQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.ui.UiScheduler;
import org.junit.jupiter.api.Test;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.plugin.DisposableScope;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Diagnostic-only regression: a failed MCP startup surfaces the last
 * deterministic stage, the original Throwable's top stack frame, and the
 * exact original Throwable as cause.
 */
final class McpStartupDiagnosticTest {

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

        @Override public CubismReadCapabilityService cubismRead() {
            captured = new IllegalStateException("deterministic read failure");
            throw captured;
        }

        @Override public ParameterQueryService parameterQuery() { return null; }
        @Override public SelectionQueryService selectionQuery() { return null; }
        @Override public ModelHierarchyQueryService modelHierarchyQuery() { return null; }
        @Override public CubismClipMaskService cubismClipMasks() { return null; }
    }
}
