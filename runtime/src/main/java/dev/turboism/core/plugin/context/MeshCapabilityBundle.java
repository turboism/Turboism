package dev.turboism.core.plugin.context;

import dev.turboism.adapter.cubism.mesh.NativeMeshMirrorBridge;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshEditService;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshEditUiService;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshMirrorAxisService;
import dev.turboism.adapter.cubism.warpalt.NativeArtMeshSymmetryBridge;
import dev.turboism.adapter.cubism.warpalt.NativeWarpAltMirrorBridge;
import dev.turboism.adapter.cubism.warpalt.RuntimeViewContextMenuRegistry;
import dev.turboism.adapter.host.RuntimeHostAdapterAccess;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.permissions.ServiceGateInstaller;
import dev.turboism.sdk.cubism.mesh.ArtMeshMirrorService;
import dev.turboism.sdk.cubism.mesh.MeshEditParticipation;
import dev.turboism.sdk.cubism.mesh.MeshEditService;
import dev.turboism.sdk.cubism.mesh.MeshEditUiService;
import dev.turboism.sdk.cubism.mesh.MeshMirrorAxisService;
import dev.turboism.sdk.cubism.mesh.MeshMirrorCounterparts;
import dev.turboism.sdk.cubism.mesh.MeshMirrorMoveParticipation;
import dev.turboism.sdk.cubism.mesh.MeshMirrorToolEligibility;
import dev.turboism.sdk.cubism.warp.WarpAltMirrorParticipation;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginService;
import dev.turboism.sdk.ui.viewcontext.ViewContextMenuRegistry;
import java.util.Objects;

/**
 * Immutable capability bundle holding mesh and warp authoring services for a plugin context.
 */
record MeshCapabilityBundle(
        MeshMirrorAxisService meshMirrorAxisService,
        MeshEditUiService meshEditUiService,
        MeshEditService meshEditService,
        MeshEditParticipation meshEditParticipationService,
        MeshMirrorCounterparts meshMirrorCounterpartsService,
        MeshMirrorToolEligibility meshMirrorToolEligibilityService,
        MeshMirrorMoveParticipation meshMirrorMoveParticipationService,
        ArtMeshMirrorService artMeshMirrorService,
        WarpAltMirrorParticipation warpAltMirrorParticipationService,
        ViewContextMenuRegistry viewContextMenuRegistry)
        implements PluginCapabilityBundle {

    MeshCapabilityBundle {
        Objects.requireNonNull(meshMirrorAxisService, "meshMirrorAxisService");
        Objects.requireNonNull(meshEditUiService, "meshEditUiService");
        Objects.requireNonNull(meshEditService, "meshEditService");
        Objects.requireNonNull(meshEditParticipationService, "meshEditParticipationService");
        Objects.requireNonNull(meshMirrorCounterpartsService, "meshMirrorCounterpartsService");
        Objects.requireNonNull(meshMirrorToolEligibilityService, "meshMirrorToolEligibilityService");
        Objects.requireNonNull(meshMirrorMoveParticipationService, "meshMirrorMoveParticipationService");
        Objects.requireNonNull(artMeshMirrorService, "artMeshMirrorService");
        Objects.requireNonNull(warpAltMirrorParticipationService, "warpAltMirrorParticipationService");
        Objects.requireNonNull(viewContextMenuRegistry, "viewContextMenuRegistry");
    }

    static MeshCapabilityBundle create(
            final RuntimeHostAdapterAccess hostAccess, final CorePluginContext.Dependencies dependencies) {
        final RuntimeMeshMirrorAxisService sharedMeshMirrorAxis =
                hostAccess == null ? new RuntimeMeshMirrorAxisService() : hostAccess.meshMirrorAxisService();
        final RuntimeMeshEditUiService sharedMeshEditUi =
                hostAccess == null ? new RuntimeMeshEditUiService() : hostAccess.meshEditUiService();
        final PermissionChecker meshPermissionChecker = PermissionChecker.from(new CubismPermissionGate(
                dependencies.descriptor().id(),
                dependencies.permissions(),
                dependencies.cubismAuditSink(),
                dependencies.clock()));
        final DisposableScope scope = dependencies.disposableScope();
        final MeshMirrorAxisService meshMirrorAxisService =
                gate(MeshMirrorAxisService.class, sharedMeshMirrorAxis, meshPermissionChecker, scope);
        final MeshEditUiService meshEditUiService =
                gate(MeshEditUiService.class, sharedMeshEditUi, meshPermissionChecker, scope);
        final MeshEditService meshEditService =
                gate(MeshEditService.class, new RuntimeMeshEditService(), meshPermissionChecker, scope);
        final MeshEditParticipation meshEditParticipationService =
                gate(MeshEditParticipation.class, NativeMeshMirrorBridge.participation(), meshPermissionChecker, scope);
        final MeshMirrorCounterparts meshMirrorCounterpartsService = gate(
                MeshMirrorCounterparts.class,
                NativeMeshMirrorBridge.pluginCounterparts(),
                meshPermissionChecker,
                scope);
        final MeshMirrorToolEligibility meshMirrorToolEligibilityService = gate(
                MeshMirrorToolEligibility.class,
                NativeMeshMirrorBridge.toolEligibility(),
                meshPermissionChecker,
                scope);
        final MeshMirrorMoveParticipation meshMirrorMoveParticipationService = gate(
                MeshMirrorMoveParticipation.class,
                NativeMeshMirrorBridge.moveParticipation(),
                meshPermissionChecker,
                scope);
        final ArtMeshMirrorService artMeshMirrorService =
                gate(ArtMeshMirrorService.class, NativeArtMeshSymmetryBridge.service(), meshPermissionChecker, scope);
        final WarpAltMirrorParticipation warpAltMirrorParticipationService = gate(
                WarpAltMirrorParticipation.class,
                NativeWarpAltMirrorBridge.moveParticipation(),
                meshPermissionChecker,
                scope);
        final ViewContextMenuRegistry viewContextMenuRegistry = gate(
                ViewContextMenuRegistry.class,
                RuntimeViewContextMenuRegistry.getInstance(),
                meshPermissionChecker,
                scope);
        return new MeshCapabilityBundle(
                meshMirrorAxisService,
                meshEditUiService,
                meshEditService,
                meshEditParticipationService,
                meshMirrorCounterpartsService,
                meshMirrorToolEligibilityService,
                meshMirrorMoveParticipationService,
                artMeshMirrorService,
                warpAltMirrorParticipationService,
                viewContextMenuRegistry);
    }

    /**
     * Wraps one mesh or warp service in its gate table. The scope is only used to adopt returned
     * registrations: these services are admitted by the catalogue that hands them out, so no
     * per-call liveness fence is layered on top of the plugin generation guard.
     */
    private static <T> T gate(
            final Class<T> api, final T delegate, final PermissionChecker checker, final DisposableScope scope) {
        return ServiceGateInstaller.install(api, delegate, checker, scope, null, ServiceGateInstaller.rowsFor(api));
    }

    @Override
    public void registerServices(final RuntimePluginServiceDirectory directory) {
        directory.installWhenPresent(PluginService.MESH_MIRROR_AXIS, () -> meshMirrorAxisService);
        directory.installWhenPresent(PluginService.MESH_EDIT, () -> meshEditService);
        directory.installWhenPresent(PluginService.MESH_EDIT_PARTICIPATION, () -> meshEditParticipationService);
        directory.installWhenPresent(PluginService.MESH_MIRROR_COUNTERPARTS, () -> meshMirrorCounterpartsService);
        directory.installWhenPresent(
                PluginService.MESH_MIRROR_TOOL_ELIGIBILITY, () -> meshMirrorToolEligibilityService);
        directory.installWhenPresent(
                PluginService.MESH_MIRROR_MOVE_PARTICIPATION, () -> meshMirrorMoveParticipationService);
        directory.installWhenPresent(
                PluginService.WARP_ALT_MIRROR_PARTICIPATION, () -> warpAltMirrorParticipationService);
        directory.installWhenPresent(PluginService.VIEW_CONTEXT_MENU, () -> viewContextMenuRegistry);
        directory.installWhenPresent(PluginService.ART_MESH_MIRROR, () -> artMeshMirrorService);
        directory.installWhenPresent(PluginService.MESH_EDIT_UI, () -> meshEditUiService);
    }
}
