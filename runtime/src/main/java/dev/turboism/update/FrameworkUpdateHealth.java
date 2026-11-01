package dev.turboism.update;

import dev.turboism.core.FrameworkBuildInfo;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Startup health handoff. Never applies or rolls back loaded files inside Cubism. */
public final class FrameworkUpdateHealth {
    private FrameworkUpdateHealth() {}

    /** Fixed installer-owned transaction location; callers must not accept it from a remote manifest. */
    public static Path workDirectory(final Path home) {
        return home.toAbsolutePath().normalize().resolve("state/runtime/update/helper");
    }

    /**
     * Runtime premain handshake for the launch lease. Runs before runtime/plugin initialization (not before every
     * framework byte is loaded): on failure the caller aborts framework startup (fail closed) rather than loading
     * framework code a helper may replace. Never invoked by startup health or shutdown; {@code USING} entries are
     * only ever removed under the transaction lock, and only once the owning process has provably exited.
     */
    public static void confirmRuntimeUsing(final Path home, final String token)
            throws IOException, InterruptedException {
        final Path root = home.toAbsolutePath().normalize();
        final Path work = workDirectory(root);
        final long pid = ProcessHandle.current().pid();
        final String started = ProcessHandle.current()
                .info()
                .startInstant()
                .orElseThrow(() -> new IOException("Runtime start instant unavailable"))
                .toString();
        try (var transaction = acquireLaunchLock(work)) {
            // A legacy launch without a launcher token registers its USING entry independently, so dead entries from
            // earlier runs would otherwise accumulate toward the fixed cap and eventually refuse every handshake in
            // this home. Reap only provably dead USING entries here, under the same lock: LAUNCHING entries and
            // live USING entries of other instances are never removed.
            FrameworkUpdateLaunchLease.reapStale(work, root, FrameworkUpdateLaunchLease.SYSTEM);
            FrameworkUpdateLaunchLease.completeToUsing(work, root, pid, started, token);
        }
    }

    /**
     * Bounded lock acquisition for the runtime handshake. Multi-instance launches may start concurrently and
     * briefly contend for the transaction lock while each registers its own USING entry; retry for a short window
     * rather than failing the handshake (which would abort framework start) on a transient hold.
     */
    private static FrameworkUpdateLock acquireLaunchLock(final Path work) throws IOException, InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try {
                return FrameworkUpdateLock.acquire(work);
            } catch (IOException busy) {
                if (System.nanoTime() >= deadline) throw busy;
                Thread.sleep(50);
            }
        }
    }

    /** Describe a startup-confirmation failure for diagnostics without leaking a stack trace into logs. */
    private static String describe(final Throwable failure) {
        final Throwable cause = failure instanceof java.util.concurrent.CompletionException
                        || failure instanceof java.util.concurrent.ExecutionException
                ? (failure.getCause() == null ? failure : failure.getCause())
                : failure;
        return cause.getClass().getSimpleName()
                + (cause.getMessage() == null || cause.getMessage().isBlank() ? "" : ": " + cause.getMessage());
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
            if (!java.nio.file.Files.exists(
                    work.resolve(FrameworkUpdateApplier.JOURNAL), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
            try (var transaction = acquireLaunchLock(work)) {
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
            diagnostic.accept("UPDATE_STARTUP_NOT_CONFIRMED: " + describe(failure));
        }
    }
}
