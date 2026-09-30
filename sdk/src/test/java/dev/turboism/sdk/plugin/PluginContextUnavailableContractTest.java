package dev.turboism.sdk.plugin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.appearance.AppearanceService;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.backup.EditorAutoBackupService;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService;
import dev.turboism.sdk.cubism.mesh.MeshEditParticipation;
import dev.turboism.sdk.cubism.mesh.MeshEditService;
import dev.turboism.sdk.cubism.mesh.MeshEditUiService;
import dev.turboism.sdk.cubism.mesh.MeshMirrorAxisService;
import dev.turboism.sdk.cubism.mesh.MeshMirrorCounterparts;
import dev.turboism.sdk.cubism.mesh.MeshMirrorMoveParticipation;
import dev.turboism.sdk.cubism.mesh.MeshMirrorToolEligibility;
import dev.turboism.sdk.cubism.model.ModelObjectService;
import dev.turboism.sdk.cubism.physics.PhysicsEditorService;
import dev.turboism.sdk.cubism.recentfile.RecentFileService;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewContributionService;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureService;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService;
import dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService;
import dev.turboism.sdk.cubism.service.query.ParameterQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.cubism.warp.WarpAltMirrorParticipation;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.hostread.AsyncHostReadService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.mcp.McpConnectionService;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.performance.PerformanceProbeService;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.runtime.CubismLogService;
import dev.turboism.sdk.runtime.RuntimeSettingsService;
import dev.turboism.sdk.script.ScriptService;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.task.PluginTaskScheduler;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.UserFileAccessService;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.dialog.HostDialogAutomationService;
import dev.turboism.sdk.ui.filter.PaletteFilterRegistry;
import dev.turboism.sdk.ui.resource.UiRasterImage;
import dev.turboism.sdk.ui.table.SceneTableService;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import dev.turboism.sdk.ui.viewcontext.ViewContextMenuRegistry;
import dev.turboism.sdk.ui.workspace.WorkspaceService;
import dev.turboism.sdk.ui.workspace.layout.WorkspaceLayoutService;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Uniform unavailability contract: every optional {@link PluginContext} accessor returns the
 * service's {@code unavailable()} singleton, and {@code isAvailable()} is {@code false} exactly
 * on that sentinel.
 */
class PluginContextUnavailableContractTest {

    private final PluginContext context = new PluginContext() {
        @Override
        public PluginDescriptor descriptor() {
            return null;
        }

        @Override
        public PluginLogger logger() {
            return null;
        }

        @Override
        public PluginPaths paths() {
            return null;
        }

        @Override
        public CubismFacade cubism() {
            return null;
        }

        @Override
        public List<PluginPermission> permissions() {
            return List.of();
        }

        @Override
        public EventBus eventBus() {
            return null;
        }

        @Override
        public ActionRegistry actions() {
            return null;
        }

        @Override
        public MenuRegistry menus() {
            return null;
        }

        @Override
        public UiScheduler uiScheduler() {
            return null;
        }

        @Override
        public DiagnosticReport diagnostics() {
            return null;
        }

        @Override
        public DisposableScope disposableScope() {
            return null;
        }
    };

    @Test
    void everyOptionalAccessorReturnsTheUnavailableSingleton() {
        assertSame(PluginLocalization.unavailable(), context.localization());
        assertSame(PluginTaskScheduler.unavailable(), context.tasks());
        assertSame(AsyncHostReadService.unavailable(), context.hostReads());
        assertSame(PluginStorage.unavailable(), context.storage());
        assertSame(ScriptService.unavailable(), context.scripts());
        assertSame(UserFileAccessService.unavailable(), context.userFiles());

        assertSame(ParameterQueryService.unavailable(), context.parameterQuery());
        assertSame(SelectionQueryService.unavailable(), context.selectionQuery());
        assertSame(ModelHierarchyQueryService.unavailable(), context.modelHierarchyQuery());
        assertSame(CubismReadCapabilityService.unavailable(), context.cubismRead());
        assertSame(ModelObjectService.unavailable(), context.modelObjects());
        assertSame(CubismClipMaskService.unavailable(), context.cubismClipMasks());
        assertSame(RecentFileService.unavailable(), context.recentFiles());
        assertSame(ScreenshotCaptureService.unavailable(), context.screenshots());
        assertSame(RecentPreviewContributionService.unavailable(), context.recentPreviews());
        assertSame(PhysicsEditorService.unavailable(), context.physicsEditor());
        assertSame(FileChooserHistoryService.unavailable(), context.fileChooserHistory());
        assertSame(EditorCommandService.unavailable(), context.editorCommands());
        assertSame(EditorAutoBackupService.unavailable(), context.backup());

        assertSame(MeshMirrorAxisService.unavailable(), context.meshMirrorAxis());
        assertSame(MeshEditService.unavailable(), context.meshEdit());
        assertSame(MeshEditParticipation.unavailable(), context.meshEditParticipation());
        assertSame(MeshMirrorCounterparts.unavailable(), context.meshMirrorCounterparts());
        assertSame(MeshMirrorToolEligibility.unavailable(), context.meshMirrorToolEligibility());
        assertSame(MeshMirrorMoveParticipation.unavailable(), context.meshMirrorMoveParticipation());
        assertSame(WarpAltMirrorParticipation.unavailable(), context.warpAltMirrorParticipation());
        assertSame(ViewContextMenuRegistry.unavailable(), context.viewContextMenu());
        assertSame(MeshEditUiService.unavailable(), context.meshEditUi());

        assertSame(MainToolbarRegistry.unavailable(), context.mainToolbar());
        assertSame(PaletteToolbarRegistry.unavailable(), context.paletteToolbar());
        assertSame(PaletteFilterRegistry.unavailable(), context.paletteFilter());
        assertSame(SceneTableService.unavailable(), context.sceneTable());
        assertSame(UiHostCapabilityService.unavailable(), context.uiHost());
        assertSame(dev.turboism.sdk.ui.resource.UiResourceService.unavailable(), context.uiResources());
        assertSame(HostDialogAutomationService.unavailable(), context.hostDialogs());
        assertSame(AppearanceService.unavailable(), context.appearance());
        assertSame(WorkspaceService.unavailable(), context.workspace());
        assertSame(WorkspaceLayoutService.unavailable(), context.workspaceLayout());
        assertSame(ContextMenuRegistry.unavailable(), context.contextMenu());
        assertSame(PluginConfigRegistry.unavailable(), context.config());
        assertSame(CubismLogService.unavailable(), context.cubismLog());
        assertSame(RuntimeSettingsService.unavailable(), context.runtimeSettings());
        assertSame(McpConnectionService.unavailable(), context.mcpConnections());
        assertSame(PerformanceProbeService.unavailable(), context.performanceStats());
    }

    @Test
    void everyUnavailableSentinelReportsUnavailable() {
        assertFalse(context.localization().isAvailable());
        assertFalse(context.tasks().isAvailable());
        assertFalse(context.hostReads().isAvailable());
        assertFalse(context.storage().isAvailable());
        assertFalse(context.scripts().isAvailable());
        assertFalse(context.userFiles().isAvailable());
        assertFalse(context.parameterQuery().isAvailable());
        assertFalse(context.selectionQuery().isAvailable());
        assertFalse(context.modelHierarchyQuery().isAvailable());
        assertFalse(context.cubismRead().isAvailable());
        assertFalse(context.modelObjects().isAvailable());
        assertFalse(context.cubismClipMasks().isAvailable());
        assertFalse(context.recentFiles().isAvailable());
        assertFalse(context.screenshots().isAvailable());
        assertFalse(context.recentPreviews().isAvailable());
        assertFalse(context.physicsEditor().isAvailable());
        assertFalse(context.fileChooserHistory().isAvailable());
        assertFalse(context.editorCommands().isAvailable());
        assertFalse(context.backup().isAvailable());
        assertFalse(context.meshMirrorAxis().isAvailable());
        assertFalse(context.meshEdit().isAvailable());
        assertFalse(context.meshEditParticipation().isAvailable());
        assertFalse(context.meshMirrorCounterparts().isAvailable());
        assertFalse(context.meshMirrorToolEligibility().isAvailable());
        assertFalse(context.meshMirrorMoveParticipation().isAvailable());
        assertFalse(context.warpAltMirrorParticipation().isAvailable());
        assertFalse(context.viewContextMenu().isAvailable());
        assertFalse(context.meshEditUi().isAvailable());
        assertFalse(context.mainToolbar().isAvailable());
        assertFalse(context.paletteToolbar().isAvailable());
        assertFalse(context.paletteFilter().isAvailable());
        assertFalse(context.sceneTable().isAvailable());
        assertFalse(context.uiHost().isAvailable());
        assertFalse(context.hostDialogs().isAvailable());
        assertFalse(context.appearance().isAvailable());
        assertFalse(context.workspace().isAvailable());
        assertFalse(context.workspaceLayout().isAvailable());
        assertFalse(context.contextMenu().isAvailable());
        assertFalse(context.config().isAvailable());
        assertFalse(context.cubismLog().isAvailable());
        assertFalse(context.runtimeSettings().isAvailable());
        assertFalse(context.mcpConnections().isAvailable());
        assertFalse(context.performanceStats().isAvailable());
    }

    /**
     * Permanent parity contract: every optional {@link PluginContext} service accessor maps to
     * exactly one {@link PluginService} member and back. An optional service accessor is a
     * {@code default} method whose return type exposes a {@code static unavailable()} sentinel
     * factory; guaranteed members are abstract accessors and must never gain a member.
     */
    @Test
    void everyOptionalServiceAccessorHasExactlyOnePluginServiceMember() {
        int optionalAccessors = 0;
        for (Method method : PluginContext.class.getDeclaredMethods()) {
            if (!method.isDefault() || !exposesUnavailableSentinel(method.getReturnType())) {
                continue;
            }
            optionalAccessors++;
            final String memberName = toMemberName(method.getName());
            assertDoesNotThrow(
                    () -> PluginService.valueOf(memberName),
                    "optional accessor " + method.getName() + "() has no PluginService." + memberName + " member");
        }
        for (PluginService service : PluginService.values()) {
            final String accessorName = toAccessorName(service.name());
            final Method accessor = assertDoesNotThrow(
                    () -> PluginContext.class.getDeclaredMethod(accessorName),
                    "PluginService." + service.name() + " has no PluginContext." + accessorName + "() accessor");
            assertTrue(
                    accessor.isDefault(),
                    "PluginService." + service.name() + " maps to guaranteed accessor " + accessorName
                            + "(), which must not carry a member");
            assertTrue(
                    exposesUnavailableSentinel(accessor.getReturnType()),
                    "PluginService." + service.name() + " maps to accessor " + accessorName
                            + "() whose return type exposes no unavailable() sentinel");
        }
        assertEquals(
                optionalAccessors,
                PluginService.values().length,
                "optional accessors and PluginService members must be a bijection");
    }

    private static boolean exposesUnavailableSentinel(final Class<?> serviceType) {
        for (Method method : serviceType.getMethods()) {
            if (method.getName().equals("unavailable")
                    && Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == 0
                    && serviceType.isAssignableFrom(method.getReturnType())) {
                return true;
            }
        }
        return false;
    }

    private static String toMemberName(final String accessorName) {
        return accessorName.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    private static String toAccessorName(final String memberName) {
        final StringBuilder name = new StringBuilder();
        for (String segment : memberName.toLowerCase(Locale.ROOT).split("_")) {
            if (name.isEmpty()) {
                name.append(segment);
            } else {
                name.append(Character.toUpperCase(segment.charAt(0))).append(segment.substring(1));
            }
        }
        return name.toString();
    }

    @Test
    void unavailableSentinelsFailClosedOnDomainCalls() {
        assertThrows(
                UnsupportedOperationException.class,
                () -> context.warpAltMirrorParticipation().participate());
        assertThrows(
                UnsupportedOperationException.class,
                () -> context.warpAltMirrorParticipation().setArmedAxis(1));
        assertThrows(
                UnsupportedOperationException.class,
                () -> context.warpAltMirrorParticipation().nativeMirrorActive());
        assertThrows(
                UnsupportedOperationException.class,
                () -> context.viewContextMenu()
                        .contributeStateButtons(new ViewContextMenuRegistry.StateButtonContribution(
                                "id", Map.of(0, new UiRasterImage(1, 1, new int[1])), 0, ignored -> {})));
        assertThrows(
                UnsupportedOperationException.class,
                () -> context.viewContextMenu().updateButtonState("id", 1));
    }
}
