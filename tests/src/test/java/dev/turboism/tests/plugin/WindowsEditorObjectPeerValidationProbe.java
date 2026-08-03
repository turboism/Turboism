package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.plugin.PluginContext;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Manual-test-only peer plugin proving a second plugin remains usable after the primary scope closes.
 */

public final class WindowsEditorObjectPeerValidationProbe implements CubismPlugin {

    private PluginContext context;
    private Thread validationThread;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        context.logger().info("Editor object peer validation probe initialized");
    }

    @Override
    public void enable() {
        // This class is packaged only in the exact-host validation bundle; always wait for the primary marker.
        try {
            final Path home = Path.of(System.getProperty("turboism.home"));
            writeStage(home.resolve("logs").resolve("editor-object-peer-scope-close.txt"), "enabled");
        } catch (Exception exception) {
            context.logger().error("Editor object peer validation enable artifact failed", exception);
        }
        validationThread = new Thread(this::runPeerValidation, "turboism-editor-object-peer-validation");
        validationThread.setDaemon(true);
        validationThread.start();
        context.logger().info("Editor object peer validation thread started");
    }

    @Override
    public void disable() {
        if (validationThread != null) validationThread.interrupt();
    }

    static final int PEER_MAX_ATTEMPTS = 600;
    static final long PEER_POLL_MILLIS = 100L;

    /** Bounded wait for the primary plugin's close-request marker; false on timeout. */
    static boolean awaitMarker(
        final Path request,
        final int maxAttempts,
        final long pollMillis
    ) throws Exception {
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (Files.exists(request)) {
                return true;
            }
            Thread.sleep(pollMillis);
        }
        return false;
    }

    private void runPeerValidation() {
        Path artifact = null;
        try {
            final Path home = Path.of(System.getProperty("turboism.home"));
            final Path request = home.resolve("state").resolve("editor-object-peer-request.txt");
            artifact = home.resolve("logs").resolve("editor-object-peer-scope-close.txt");
            writeStage(artifact, "waiting-for-primary");
            if (!awaitMarker(request, PEER_MAX_ATTEMPTS, PEER_POLL_MILLIS)) {
                throw new IllegalStateException("Primary plugin close request was not observed within the bounded wait");
            }
            writeStage(artifact, "primary-marker-seen");
            final CubismModel model = awaitModel();
            final boolean modelUsable = onHostThread(() -> model.id() != null);
            final boolean meshUsable = onHostThread(() -> !model.drawables().all().isEmpty()
                && model.drawables().all().get(0).geometry() != null);
            final boolean warpUsable = onHostThread(() -> !model.warpDeformers().all().isEmpty()
                && model.warpDeformers().all().get(0).grid() != null);
            final boolean rotationUsable = onHostThread(() -> !model.rotationDeformers().all().isEmpty()
                && model.rotationDeformers().all().get(0).form() != null);
            final boolean passed = modelUsable && meshUsable && warpUsable && rotationUsable;
            Files.createDirectories(artifact.getParent());
            Files.writeString(
                artifact,
                "status=" + (passed ? "PASS" : "FAIL")
                    + "\nphase=peer-after-primary-scope-close"
                    + "\nsecondPluginUsable=" + passed
                    + "\nmodelUsable=" + modelUsable
                    + "\nmeshUsable=" + meshUsable
                    + "\nwarpUsable=" + warpUsable
                    + "\nrotationUsable=" + rotationUsable + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception exception) {
            context.logger().error("Editor object peer validation failed", exception);
            if (artifact == null) return;
            try {
                Files.createDirectories(artifact.getParent());
                Files.writeString(
                    artifact,
                    "status=FAIL\nphase=peer-after-primary-scope-close\nerror=" + exception + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                );
            } catch (Exception ignored) {
                // Manual evidence probe cannot recover when its artifact path is unavailable.
            }
        }
    }

    private CubismModel awaitModel() throws Exception {
        Exception lastFailure = null;
        for (int attempt = 0; attempt < 600; attempt++) {
            try {
                final CubismModel model = onHostThread(() -> context.cubism().model().active());
                if (onHostThread(() -> !model.drawables().all().isEmpty()
                    && !model.warpDeformers().all().isEmpty()
                    && !model.rotationDeformers().all().isEmpty())) {
                    return model;
                }
            } catch (Exception exception) {
                lastFailure = exception;
            }
            Thread.sleep(100L);
        }
        throw new IllegalStateException("Peer plugin did not observe an active Editor model", lastFailure);
    }

    private void writeStage(final Path artifact, final String stage) throws Exception {
        Files.createDirectories(artifact.getParent());
        Files.writeString(
            artifact,
            "status=RUNNING\nphase=" + stage + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private <T> T onHostThread(final java.util.concurrent.Callable<T> operation) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return operation.call();
        final java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<Exception> failure = new java.util.concurrent.atomic.AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(operation.call());
            } catch (Exception exception) {
                failure.set(exception);
            }
        });
        if (failure.get() != null) throw failure.get();
        return result.get();
    }
}
