package dev.turboism.core.plugin.context;

import dev.turboism.adapter.cubism.mesh.AuthorizedMeshEditParticipation;
import dev.turboism.adapter.cubism.mesh.AuthorizedMeshEditService;
import dev.turboism.adapter.cubism.mesh.AuthorizedMeshEditUiService;
import dev.turboism.adapter.cubism.mesh.AuthorizedMeshMirrorAxisService;
import dev.turboism.adapter.cubism.mesh.AuthorizedMeshMirrorCounterparts;
import dev.turboism.adapter.cubism.mesh.AuthorizedMeshMirrorMoveParticipation;
import dev.turboism.adapter.cubism.mesh.AuthorizedMeshMirrorToolEligibility;
import dev.turboism.adapter.cubism.mesh.NativeMeshMirrorBridge;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshEditService;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshEditUiService;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshMirrorAxisService;
import dev.turboism.adapter.cubism.warpalt.AuthorizedArtMeshMirrorService;
import dev.turboism.adapter.cubism.warpalt.AuthorizedViewContextMenuRegistry;
import dev.turboism.adapter.cubism.warpalt.AuthorizedWarpAltMirrorParticipation;
import dev.turboism.adapter.cubism.warpalt.NativeArtMeshSymmetryBridge;
import dev.turboism.adapter.cubism.warpalt.NativeWarpAltMirrorBridge;
import dev.turboism.adapter.cubism.warpalt.RuntimeViewContextMenuRegistry;
import dev.turboism.adapter.host.RuntimeHostAdapterAccess;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.mesh.ArtMeshMirrorService;
import dev.turboism.sdk.cubism.mesh.MeshEditParticipation;
import dev.turboism.sdk.cubism.mesh.MeshEditService;
import dev.turboism.sdk.cubism.mesh.MeshEditUiService;
import dev.turboism.sdk.cubism.mesh.MeshMirrorAxisService;
import dev.turboism.sdk.cubism.mesh.MeshMirrorCounterparts;
import dev.turboism.sdk.cubism.mesh.MeshMirrorMoveParticipation;
import dev.turboism.sdk.cubism.mesh.MeshMirrorToolEligibility;
import dev.turboism.sdk.cubism.warp.WarpAltMirrorParticipation;
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
        final MeshMirrorAxisService meshMirrorAxisService =
                new AuthorizedMeshMirrorAxisService(sharedMeshMirrorAxis, meshPermissionChecker);
        final MeshEditUiService meshEditUiService = new AuthorizedMeshEditUiService(
                sharedMeshEditUi, meshPermissionChecker, dependencies.disposableScope());
        final MeshEditService meshEditService =
                new AuthorizedMeshEditService(new RuntimeMeshEditService(), meshPermissionChecker);
        final MeshEditParticipation meshEditParticipationService = new AuthorizedMeshEditParticipation(
                NativeMeshMirrorBridge.participation(), meshPermissionChecker, dependencies.disposableScope());
        final MeshMirrorCounterparts meshMirrorCounterpartsService = new AuthorizedMeshMirrorCounterparts(
                NativeMeshMirrorBridge.counterparts(), meshPermissionChecker, dependencies.disposableScope());
        final MeshMirrorToolEligibility meshMirrorToolEligibilityService = new AuthorizedMeshMirrorToolEligibility(
                NativeMeshMirrorBridge.toolEligibility(), meshPermissionChecker, dependencies.disposableScope());
        final MeshMirrorMoveParticipation meshMirrorMoveParticipationService =
                new AuthorizedMeshMirrorMoveParticipation(
                        NativeMeshMirrorBridge.moveParticipation(),
                        meshPermissionChecker,
                        dependencies.disposableScope());
        final ArtMeshMirrorService artMeshMirrorService = new AuthorizedArtMeshMirrorService(
                NativeArtMeshSymmetryBridge.service(), meshPermissionChecker, dependencies.disposableScope());
        final WarpAltMirrorParticipation warpAltMirrorParticipationService = new AuthorizedWarpAltMirrorParticipation(
                NativeWarpAltMirrorBridge.moveParticipation(), meshPermissionChecker, dependencies.disposableScope());
        final ViewContextMenuRegistry viewContextMenuRegistry = new AuthorizedViewContextMenuRegistry(
                RuntimeViewContextMenuRegistry.getInstance(), meshPermissionChecker);
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
