package dev.turboism.core.plugin.context;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.backup.AutoBackupAdapter;
import dev.turboism.adapter.cubism.command.EditorCommandAdapter;
import dev.turboism.adapter.cubism.command.EditorFileCommandResolver;
import dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator;
import dev.turboism.adapter.cubism.physics.PhysicsEditorCoordinator;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Guards the DefaultCubismServicesFactory wiring: every convenience constructor
 * that receives a {@link RuntimeHostAdapters} must forward its connected
 * auto-backup adapter (never hardcode the safe-mode adapter), so a plugin
 * context built from a connected host bundle exposes a working backup service.
 */
class DefaultCubismServicesFactoryAutoBackupWiringTest {

    @Test
    void convenienceConstructorForwardsTheConnectedAutoBackupAdapter() {
        final AutoBackupAdapter connected = AutoBackupAdapter.connected(new AutoBackupAdapter.HostOperations() {
            @Override
            public AutoBackupAdapter.Snapshot settings() {
                return new AutoBackupAdapter.Snapshot(true, 3, 128, new File("backup"));
            }

            @Override
            public AutoBackupAdapter.Snapshot applySettings(final AutoBackupAdapter.Snapshot target) {
                return target;
            }

            @Override
            public List<AutoBackupAdapter.Document> documents() {
                return List.of();
            }

            @Override
            public void triggerBackupNow() {
            }

            @Override
            public File saveDocumentFor(final File matchFile, final long timestampMillis) {
                return null;
            }
        });
        final RuntimeHostAdapters hostAdapters = connectedHostAdapters(connected);

        // The 8-arg convenience constructor (hostAdapters + editor command slots)
        // is the exact chain the real host uses; it must forward the connected
        // adapter instead of the safe-mode singleton.
        final DefaultCubismServicesFactory factory = new DefaultCubismServicesFactory(
            hostAdapters,
            unavailableModelAccess(),
            new ParameterLifecycleCoordinator(),
            new PartLifecycleCoordinator(),
            new EditorObjectLifecycleCoordinator(),
            new PhysicsEditorCoordinator(),
            EditorCommandAdapter.unavailable(),
            EditorFileCommandResolver.unavailable()
        );

        assertSame(connected, factory.autoBackupAdapter(),
            "the convenience constructor must forward hostAdapters.autoBackup()");
    }

    @Test
    void convenienceConstructorKeepsSafeModeForTheSafeModeBundle() {
        final DefaultCubismServicesFactory factory = new DefaultCubismServicesFactory(
            RuntimeHostAdapters.safeMode(),
            unavailableModelAccess(),
            new ParameterLifecycleCoordinator(),
            new PartLifecycleCoordinator(),
            new EditorObjectLifecycleCoordinator(),
            new PhysicsEditorCoordinator(),
            EditorCommandAdapter.unavailable(),
            EditorFileCommandResolver.unavailable()
        );

        assertSame(AutoBackupAdapter.SafeMode.INSTANCE, factory.autoBackupAdapter(),
            "the no-host path must keep the safe-mode adapter");
    }

    private static RuntimeHostAdapters connectedHostAdapters(final AutoBackupAdapter autoBackup) {
        final RuntimeHostAdapters safe = RuntimeHostAdapters.safeMode();
        return new RuntimeHostAdapters(
            safe.themeStatus(),
            safe.renderStatus(),
            safe.projectWorkspace(),
            safe.clipMaskRead(),
            safe.statusToolbar(),
            safe.uiSurface(),
            safe.recentFiles(),
            safe.screenshots(),
            safe.recentPreviews(),
            autoBackup
        );
    }

    private static CubismModelAccess unavailableModelAccess() {
        return () -> {
            throw new IllegalStateException("no verified active Cubism Core model is available");
        };
    }
}
