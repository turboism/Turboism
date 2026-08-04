package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.ui.contribution.EditorUiContributionProvider;
import dev.turboism.ui.appearance.AppearanceHostProvider;
import dev.turboism.ui.appearance.UnavailableAppearanceHostProvider;

import java.util.List;
import java.util.Objects;

/** Owns the adapter bundle and all resources allocated for one exact host connection. */
interface HostAdapterConnection extends AutoCloseable {

    RuntimeHostAdapters adapters();

    default CubismModelAccess modelAccess() {
        return UnavailableCubismModelAccess.INSTANCE;
    }

    default dev.turboism.sdk.cubism.core.CoreRuntimeInfo coreRuntimeInfo() {
        return DynamicCoreRuntimeInfo.unavailableRuntime();
    }

    default VerifiedMemberResolver editorModelResolver() {
        throw new IllegalStateException("Verified Editor model resolver is unavailable.");
    }

    default VerifiedMemberResolver boundingBoxOverlayResolver() {
        throw new IllegalStateException("Verified bounding-box overlay resolver is unavailable.");
    }

    default dev.turboism.ui.workspace.WorkspaceHostProvider workspaceProvider() {
        return null;
    }

    default List<EditorUiContributionProvider> editorUiProviders(final long hostGeneration) {
        if (hostGeneration <= 0) {
            throw new IllegalArgumentException("hostGeneration must be positive");
        }
        return List.of();
    }

    default AppearanceHostProvider appearanceProvider() {
        return new UnavailableAppearanceHostProvider();
    }

    default dev.turboism.ui.context.NativeObjectContextMenuBridge.Handler objectContextMenuHandler(
        final long hostGeneration
    ) {
        if (hostGeneration <= 0) {
            throw new IllegalArgumentException("hostGeneration must be positive");
        }
        return null;
    }

    default dev.turboism.ui.context.NativeParameterPointContextMenuBridge.Handler parameterPointMenuHandler(
        final long hostGeneration
    ) {
        if (hostGeneration <= 0) throw new IllegalArgumentException("hostGeneration must be positive");
        return null;
    }


    default dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator dockMaintenance() {
        return new dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator();
    }

    @Override
    void close() throws Exception;

    static HostAdapterConnection of(final RuntimeHostAdapters adapters) {
        return of(adapters, UnavailableCubismModelAccess.INSTANCE);
    }

    static HostAdapterConnection of(
        final RuntimeHostAdapters adapters,
        final CubismModelAccess modelAccess
    ) {
        return of(adapters, modelAccess, null);
    }

    static HostAdapterConnection of(
        final RuntimeHostAdapters adapters,
        final CubismModelAccess modelAccess,
        final VerifiedMemberResolver editorModelResolver
    ) {
        return of(adapters, modelAccess, editorModelResolver, new UnavailableAppearanceHostProvider());
    }

    static HostAdapterConnection of(
        final RuntimeHostAdapters adapters,
        final CubismModelAccess modelAccess,
        final VerifiedMemberResolver editorModelResolver,
        final AppearanceHostProvider appearanceProvider
    ) {
        return of(
            adapters,
            modelAccess,
            editorModelResolver,
            appearanceProvider,
            DynamicCoreRuntimeInfo.unavailableRuntime(),
            null
        );
    }

    static HostAdapterConnection of(
        final RuntimeHostAdapters adapters,
        final CubismModelAccess modelAccess,
        final VerifiedMemberResolver editorModelResolver,
        final AppearanceHostProvider appearanceProvider,
        final dev.turboism.sdk.cubism.core.CoreRuntimeInfo coreRuntimeInfo,
        final AutoCloseable coreOwner
    ) {
        final RuntimeHostAdapters ownedAdapters = Objects.requireNonNull(adapters, "adapters");
        final CubismModelAccess ownedModelAccess = Objects.requireNonNull(modelAccess, "modelAccess");
        final AppearanceHostProvider ownedAppearance = Objects.requireNonNull(appearanceProvider, "appearanceProvider");
        final dev.turboism.sdk.cubism.core.CoreRuntimeInfo ownedCoreRuntime =
            Objects.requireNonNull(coreRuntimeInfo, "coreRuntimeInfo");
        return new HostAdapterConnection() {
            @Override
            public RuntimeHostAdapters adapters() {
                return ownedAdapters;
            }

            @Override
            public CubismModelAccess modelAccess() {
                return ownedModelAccess;
            }

            @Override
            public dev.turboism.sdk.cubism.core.CoreRuntimeInfo coreRuntimeInfo() {
                return ownedCoreRuntime;
            }

            @Override
            public VerifiedMemberResolver editorModelResolver() {
                if (editorModelResolver == null) {
                    return HostAdapterConnection.super.editorModelResolver();
                }
                return editorModelResolver;
            }

            @Override
            public AppearanceHostProvider appearanceProvider() {
                return ownedAppearance;
            }

            @Override
            public void close() throws Exception {
                if (coreOwner != null) coreOwner.close();
            }
        };
    }
}
