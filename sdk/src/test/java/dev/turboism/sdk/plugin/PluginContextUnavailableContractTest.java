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
import dev.turboism.sdk.cubism.export.ExportSettingsContributionService;
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
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Uniform unavailability contract: every optional {@link PluginContext} accessor returns the
 * service's {@code unavailable()} singleton, {@code isAvailable()} is {@code false} exactly
 * on that sentinel, and the service directory reports directory-resolved services as absent
 * rather than surfacing a sentinel.
 */
class PluginContextUnavailableContractTest {

    /** {@link PluginService} members still backed by a {@link PluginContext} accessor. */
    private static final Set<PluginService> ACCESSOR_BACKED = Set.of(
            PluginService.LOCALIZATION,
            PluginService.TASKS,
            PluginService.HOST_READS,
            PluginService.STORAGE,
            PluginService.SCRIPTS,
            PluginService.USER_FILES,
            PluginService.PARAMETER_QUERY,
            PluginService.SELECTION_QUERY,
            PluginService.MODEL_HIERARCHY_QUERY,
            PluginService.CUBISM_READ,
            PluginService.MODEL_OBJECTS,
            PluginService.CONFIG);

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
        assertSame(PluginConfigRegistry.unavailable(), context.config());
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
        assertFalse(context.config().isAvailable());
    }

    /**
     * Directory-resolved services that have no {@link PluginContext} accessor degrade through
     * {@code find(...).orElse(Service.unavailable())}: on a bare context every one resolves to
     * its own unavailable sentinel, so migrated call sites keep the pre-directory behavior.
     */
    @Test
    void directoryOnlyServicesDegradeToTheirUnavailableSentinels() {
        assertSame(
                CubismClipMaskService.unavailable(),
                context.services().find(CubismClipMaskService.class).orElse(CubismClipMaskService.unavailable()));
        assertSame(
                RecentFileService.unavailable(),
                context.services().find(RecentFileService.class).orElse(RecentFileService.unavailable()));
        assertSame(
                ScreenshotCaptureService.unavailable(),
                context.services().find(ScreenshotCaptureService.class).orElse(ScreenshotCaptureService.unavailable()));
        assertSame(
                RecentPreviewContributionService.unavailable(),
                context.services()
                        .find(RecentPreviewContributionService.class)
                        .orElse(RecentPreviewContributionService.unavailable()));
        assertSame(
                PhysicsEditorService.unavailable(),
                context.services().find(PhysicsEditorService.class).orElse(PhysicsEditorService.unavailable()));
        assertSame(
                FileChooserHistoryService.unavailable(),
                context.services()
                        .find(FileChooserHistoryService.class)
                        .orElse(FileChooserHistoryService.unavailable()));
        assertSame(
                EditorCommandService.unavailable(),
                context.services().find(EditorCommandService.class).orElse(EditorCommandService.unavailable()));
        assertSame(
                EditorAutoBackupService.unavailable(),
                context.services().find(EditorAutoBackupService.class).orElse(EditorAutoBackupService.unavailable()));

        assertSame(
                MeshMirrorAxisService.unavailable(),
                context.services().find(MeshMirrorAxisService.class).orElse(MeshMirrorAxisService.unavailable()));
        assertSame(
                MeshEditService.unavailable(),
                context.services().find(MeshEditService.class).orElse(MeshEditService.unavailable()));
        assertSame(
                MeshEditParticipation.unavailable(),
                context.services().find(MeshEditParticipation.class).orElse(MeshEditParticipation.unavailable()));
        assertSame(
                MeshMirrorCounterparts.unavailable(),
                context.services().find(MeshMirrorCounterparts.class).orElse(MeshMirrorCounterparts.unavailable()));
        assertSame(
                MeshMirrorToolEligibility.unavailable(),
                context.services()
                        .find(MeshMirrorToolEligibility.class)
                        .orElse(MeshMirrorToolEligibility.unavailable()));
        assertSame(
                MeshMirrorMoveParticipation.unavailable(),
                context.services()
                        .find(MeshMirrorMoveParticipation.class)
                        .orElse(MeshMirrorMoveParticipation.unavailable()));
        assertSame(
                WarpAltMirrorParticipation.unavailable(),
                context.services()
                        .find(WarpAltMirrorParticipation.class)
                        .orElse(WarpAltMirrorParticipation.unavailable()));
        assertSame(
                ViewContextMenuRegistry.unavailable(),
                context.services().find(ViewContextMenuRegistry.class).orElse(ViewContextMenuRegistry.unavailable()));
        assertSame(
                MeshEditUiService.unavailable(),
                context.services().find(MeshEditUiService.class).orElse(MeshEditUiService.unavailable()));

        assertSame(
                MainToolbarRegistry.unavailable(),
                context.services().find(MainToolbarRegistry.class).orElse(MainToolbarRegistry.unavailable()));
        assertSame(
                PaletteToolbarRegistry.unavailable(),
                context.services().find(PaletteToolbarRegistry.class).orElse(PaletteToolbarRegistry.unavailable()));
        assertSame(
                PaletteFilterRegistry.unavailable(),
                context.services().find(PaletteFilterRegistry.class).orElse(PaletteFilterRegistry.unavailable()));
        assertSame(
                SceneTableService.unavailable(),
                context.services().find(SceneTableService.class).orElse(SceneTableService.unavailable()));
        assertSame(
                UiHostCapabilityService.unavailable(),
                context.services().find(UiHostCapabilityService.class).orElse(UiHostCapabilityService.unavailable()));
        assertSame(
                dev.turboism.sdk.ui.resource.UiResourceService.unavailable(),
                context.services()
                        .find(dev.turboism.sdk.ui.resource.UiResourceService.class)
                        .orElse(dev.turboism.sdk.ui.resource.UiResourceService.unavailable()));
        assertSame(
                HostDialogAutomationService.unavailable(),
                context.services()
                        .find(HostDialogAutomationService.class)
                        .orElse(HostDialogAutomationService.unavailable()));
        assertSame(
                AppearanceService.unavailable(),
                context.services().find(AppearanceService.class).orElse(AppearanceService.unavailable()));
        assertSame(
                WorkspaceService.unavailable(),
                context.services().find(WorkspaceService.class).orElse(WorkspaceService.unavailable()));
        assertSame(
                WorkspaceLayoutService.unavailable(),
                context.services().find(WorkspaceLayoutService.class).orElse(WorkspaceLayoutService.unavailable()));
        assertSame(
                ContextMenuRegistry.unavailable(),
                context.services().find(ContextMenuRegistry.class).orElse(ContextMenuRegistry.unavailable()));
        assertSame(
                CubismLogService.unavailable(),
                context.services().find(CubismLogService.class).orElse(CubismLogService.unavailable()));
        assertSame(
                RuntimeSettingsService.unavailable(),
                context.services().find(RuntimeSettingsService.class).orElse(RuntimeSettingsService.unavailable()));
        assertSame(
                McpConnectionService.unavailable(),
                context.services().find(McpConnectionService.class).orElse(McpConnectionService.unavailable()));
        assertSame(
                PerformanceProbeService.unavailable(),
                context.services().find(PerformanceProbeService.class).orElse(PerformanceProbeService.unavailable()));
        assertSame(
                ExportSettingsContributionService.unavailable(),
                context.services()
                        .find(ExportSettingsContributionService.class)
                        .orElse(ExportSettingsContributionService.unavailable()));
        assertSame(
                dev.turboism.sdk.action.ActionCatalogService.unavailable(),
                context.services()
                        .find(dev.turboism.sdk.action.ActionCatalogService.class)
                        .orElse(dev.turboism.sdk.action.ActionCatalogService.unavailable()));
    }

    /**
     * Permanent parity contract: accessor-backed {@link PluginService} members map to exactly
     * one optional {@link PluginContext} accessor and back; every other member is
     * directory-only and must never gain a context accessor. An optional service accessor is
     * a {@code default} method whose return type exposes a {@code static unavailable()}
     * sentinel factory; guaranteed members are abstract accessors and must never gain a
     * member.
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
            if (!ACCESSOR_BACKED.contains(service)) {
                assertThrows(
                        NoSuchMethodException.class,
                        () -> PluginContext.class.getDeclaredMethod(accessorName),
                        "directory-only " + service + " must not gain a PluginContext." + accessorName + "() accessor");
                continue;
            }
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
                ACCESSOR_BACKED.size(),
                "optional accessors and accessor-backed PluginService members must be a bijection");
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
                () -> WarpAltMirrorParticipation.unavailable().participate());
        assertThrows(
                UnsupportedOperationException.class,
                () -> WarpAltMirrorParticipation.unavailable().setArmedAxis(1));
        assertThrows(
                UnsupportedOperationException.class,
                () -> WarpAltMirrorParticipation.unavailable().nativeMirrorActive());
        assertThrows(
                UnsupportedOperationException.class,
                () -> ViewContextMenuRegistry.unavailable()
                        .contributeStateButtons(new ViewContextMenuRegistry.StateButtonContribution(
                                "id", Map.of(0, new UiRasterImage(1, 1, new int[1])), 0, ignored -> {})));
        assertThrows(
                UnsupportedOperationException.class,
                () -> ViewContextMenuRegistry.unavailable().updateButtonState("id", 1));
    }
}
