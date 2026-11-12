package dev.turboism.kernel.context;

import dev.turboism.adapter.cubism.mesh.NativeMeshMirrorBridge;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshEditContributionSurface;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshEditService;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshEditUiService;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshMirrorAxisService;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshMirrorControl;
import dev.turboism.adapter.cubism.warpalt.NativeArtMeshSymmetryBridge;
import dev.turboism.adapter.cubism.warpalt.NativeWarpAltMirrorBridge;
import dev.turboism.adapter.cubism.warpalt.RuntimeViewContextMenuRegistry;
import dev.turboism.adapter.host.RuntimeHostAdapterAccess;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.permissions.ServiceGateInstaller;
import dev.turboism.sdk.cubism.mesh.ArtMeshMirrorService;
import dev.turboism.sdk.cubism.mesh.MeshEditContributionSurface;
import dev.turboism.sdk.cubism.mesh.MeshEditService;
import dev.turboism.sdk.cubism.mesh.MeshMirrorControl;
import dev.turboism.sdk.cubism.warp.WarpAltMirrorParticipation;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginService;
import dev.turboism.sdk.ui.viewcontext.ViewContextMenuRegistry;
import java.util.Objects;

/**
 * Immutable capability bundle holding mesh and warp authoring services for a plugin context.
 */
record MeshCapabilityBundle(
        MeshEditService meshEditService,
        MeshMirrorControl meshMirrorControlService,
        MeshEditContributionSurface meshEditContributionSurfaceService,
        ArtMeshMirrorService artMeshMirrorService,
        WarpAltMirrorParticipation warpAltMirrorParticipationService,
        ViewContextMenuRegistry viewContextMenuRegistry)
        implements PluginCapabilityBundle {

    MeshCapabilityBundle {
        Objects.requireNonNull(meshEditService, "meshEditService");
        Objects.requireNonNull(meshMirrorControlService, "meshMirrorControlService");
        Objects.requireNonNull(meshEditContributionSurfaceService, "meshEditContributionSurfaceService");
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
        final MeshEditService meshEditService =
                gate(MeshEditService.class, new RuntimeMeshEditService(), meshPermissionChecker, scope);
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
        final MeshMirrorControl meshMirrorControlService = gate(
                MeshMirrorControl.class,
                RuntimeMeshMirrorControl.forSession(sharedMeshMirrorAxis),
                meshPermissionChecker,
                scope);
        final MeshEditContributionSurface meshEditContributionSurfaceService = gate(
                MeshEditContributionSurface.class,
                new RuntimeMeshEditContributionSurface(NativeMeshMirrorBridge.participation(), sharedMeshEditUi),
                meshPermissionChecker,
                scope);
        return new MeshCapabilityBundle(
                meshEditService,
                meshMirrorControlService,
                meshEditContributionSurfaceService,
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
        directory.installWhenPresent(PluginService.MESH_EDIT, () -> meshEditService);
        directory.installWhenPresent(
                PluginService.WARP_ALT_MIRROR_PARTICIPATION, () -> warpAltMirrorParticipationService);
        directory.installWhenPresent(PluginService.VIEW_CONTEXT_MENU, () -> viewContextMenuRegistry);
        directory.installWhenPresent(PluginService.ART_MESH_MIRROR, () -> artMeshMirrorService);
        directory.installWhenPresent(PluginService.MESH_MIRROR_CONTROL, () -> meshMirrorControlService);
        directory.installWhenPresent(PluginService.MESH_EDIT_CONTRIBUTION, () -> meshEditContributionSurfaceService);
    }
}
