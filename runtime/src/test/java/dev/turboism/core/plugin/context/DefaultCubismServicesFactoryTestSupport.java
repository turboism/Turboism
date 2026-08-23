package dev.turboism.core.plugin.context;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator;
import dev.turboism.adapter.cubism.physics.PhysicsEditorCoordinator;
import dev.turboism.adapter.host.PluginScopedCubismModelAccess;
import dev.turboism.adapter.cubism.command.EditorCommandAdapter;
import dev.turboism.adapter.cubism.command.EditorFileCommandResolver;
import dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorSession;
import dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasEditorUi;
import dev.turboism.adapter.cubism.textureatlas.RuntimeTextureAtlasLayoutAlgorithmRegistry;
import dev.turboism.adapter.cubism.textureatlas.TextureAtlasLayoutCoordinator;
import dev.turboism.sdk.cubism.core.CoreCapabilities;
import dev.turboism.sdk.cubism.core.CoreRuntimeInfo;
import dev.turboism.sdk.cubism.core.CoreVersion;
import dev.turboism.sdk.cubism.core.MocInspector;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.ui.appearance.control.PaletteAppearanceCoordinator;

final class DefaultCubismServicesFactoryTestSupport {
    private DefaultCubismServicesFactoryTestSupport() {
    }

    static DefaultCubismServicesFactory withEditorCommands(
        final RuntimeHostAdapters hostAdapters,
        final CubismModelAccess modelAccess,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle,
        final EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final PhysicsEditorCoordinator physicsEditorCoordinator,
        final EditorCommandAdapter editorCommands,
        final EditorFileCommandResolver editorFiles
    ) {
        final HostSnapshotSource appearanceSource = PluginScopedCubismModelAccess.appearanceSource(
            hostAdapters.projectWorkspace(), modelAccess
        );
        return new DefaultCubismServicesFactory(
            hostAdapters,
            () -> java.util.Optional.of("5.3.02"),
            modelAccess,
            unavailableCoreRuntime(),
            parameterLifecycle,
            partLifecycle,
            editorObjectLifecycle,
            physicsEditorCoordinator,
            appearanceSource,
            new PaletteAppearanceCoordinator(),
            new TextureAtlasLayoutCoordinator(),
            new dev.turboism.adapter.cubism.textureatlas.TextureAtlasNativeInvocationCoordinator(),
            new RuntimeTextureAtlasEditorUi(),
            RuntimeTextureAtlasEditorSession.unavailable(),
            new RuntimeTextureAtlasLayoutAlgorithmRegistry(),
            editorCommands,
            editorFiles,
            hostAdapters.autoBackup(),
            dev.turboism.sdk.cubism.history.CubismHistory.unavailable()
        );
    }

    static DefaultCubismServicesFactory withModelAccess(
        final RuntimeHostAdapters hostAdapters,
        final CubismModelAccess modelAccess
    ) {
        return withEditorCommands(
            hostAdapters,
            modelAccess,
            new ParameterLifecycleCoordinator(),
            new PartLifecycleCoordinator(),
            new EditorObjectLifecycleCoordinator(),
            new PhysicsEditorCoordinator(),
            EditorCommandAdapter.unavailable(),
            EditorFileCommandResolver.unavailable()
        );
    }

    private static CoreRuntimeInfo unavailableCoreRuntime() {
        return new CoreRuntimeInfo() {
            private UnsupportedOperationException unavailable() {
                return new UnsupportedOperationException("Core runtime metadata is unavailable.");
            }

            @Override public CoreVersion version() { throw unavailable(); }
            @Override public CoreCapabilities capabilities() { throw unavailable(); }
            @Override public MocInspector mocInspector() { throw unavailable(); }
        };
    }
}
