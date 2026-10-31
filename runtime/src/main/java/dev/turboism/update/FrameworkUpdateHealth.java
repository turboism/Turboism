package dev.turboism.update;

import dev.turboism.core.FrameworkBuildInfo;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Startup health handoff. Never applies or rolls back loaded files inside Cubism. */
public final class FrameworkUpdateHealth {
    private FrameworkUpdateHealth() {}

    /** Fixed installer-owned transaction location; callers must not accept it from a remote manifest. */
    public static Path workDirectory(final Path home) {
        return home.toAbsolutePath().normalize().resolve("state/runtime/update/helper");
    }

    /** Invoke on the startup worker only after host, shell and plugin initialization have completed. */
    public static void startupSucceeded(final Path home, final Consumer<String> diagnostic) {
        try {
            if (javax.swing.SwingUtilities.isEventDispatchThread()) {
                throw new java.io.IOException("Startup health verification cannot run on EDT");
            }
            final Path artifact = FrameworkRuntimeArtifact.resolve();
            if (artifact == null) {
                diagnostic.accept("UPDATE_STARTUP_NOT_CONFIRMED: runtime artifact unavailable");
                return;
            }
            final Path work = workDirectory(home);
            if (!java.nio.file.Files.exists(work.resolve(FrameworkUpdateApplier.JOURNAL),
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
            try (var transaction = FrameworkUpdateLock.acquire(work)) {
                final var applier = new FrameworkUpdateApplier(home, work);
                if (applier.hasTrackedReceipt()) {
                    final var keys = FrameworkUpdateTrust.bundled().activeKeys();
                    if (keys.isEmpty()) throw new java.io.IOException("Framework signing keys unavailable");
                    // Confirm the whole newly installed signed layout, not only that an agent happened to start.
                    new FrameworkInstallationReceiptLoader(home, new UpdateManifestVerifier(keys)).load();
                }
                if (applier.confirmHealthy(FrameworkBuildInfo.current().version(), artifact)) {
                    diagnostic.accept("UPDATE_STARTUP_CONFIRMED");
                }
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            // Confirmation failure must retain backups, not destroy the user's modeling session.
            diagnostic.accept("UPDATE_STARTUP_NOT_CONFIRMED: " + failure.getClass().getSimpleName());
        }
    }
}
