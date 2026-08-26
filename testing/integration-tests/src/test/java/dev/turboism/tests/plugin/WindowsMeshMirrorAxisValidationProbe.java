package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.plugin.PluginContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/**
 * Manual-test-only SDK probe for the mesh mirror-axis rotation hook.
 *
 * <p>Writes machine-readable evidence to the directory given by
 * {@code -Dturboism.meshMirrorProbe.output}: a one-shot SDK roundtrip of the
 * mirror-axis angle and a periodic state dump (current angle, mesh/deformer
 * counts). The probe never touches runtime internals or host UI objects.</p>
 */
public final class WindowsMeshMirrorAxisValidationProbe implements CubismPlugin {

    private static final long DUMP_PERIOD_MILLIS = 3000L;

    private PluginContext context;
    private Path outputDir;
    private Thread dumpThread;
    private volatile boolean running;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        final String configured = System.getProperty("turboism.meshMirrorProbe.output");
        if (configured != null && !configured.isBlank()) {
            outputDir = Path.of(configured);
        }
    }

    @Override
    public void enable() {
        running = true;
        dumpThread = new Thread(this::run, "turboism-mesh-mirror-probe");
        dumpThread.setDaemon(true);
        dumpThread.start();
    }

    @Override
    public void disable() {
        running = false;
        if (dumpThread != null) {
            dumpThread.interrupt();
            dumpThread = null;
        }
        dumpState("STOPPED");
    }

    @Override
    public void shutdown() {
        running = false;
        dumpState("STOPPED");
    }

    private void run() {
        recordSdkRoundtrip();
        while (running && !Thread.currentThread().isInterrupted()) {
            dumpState("RUNNING");
            try {
                Thread.sleep(DUMP_PERIOD_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void recordSdkRoundtrip() {
        if (outputDir == null) return;
        try {
            Files.createDirectories(outputDir);
            final float initial = context.meshMirrorAxis().currentAngleDegrees();
            context.meshMirrorAxis().setCurrentAngleDegrees(45.0f);
            final float afterSet = context.meshMirrorAxis().currentAngleDegrees();
            context.meshMirrorAxis().setCurrentAngleDegrees(0.0f);
            final float afterRestore = context.meshMirrorAxis().currentAngleDegrees();
            final String report = "status=SDK_ROUNDTRIP_DONE\n"
                + "time=" + Instant.now() + "\n"
                + "initialAngleDegrees=" + initial + "\n"
                + "afterSet45Degrees=" + afterSet + "\n"
                + "afterRestore0Degrees=" + afterRestore + "\n"
                + "roundtripPassed=" + (afterSet == 45.0f && afterRestore == 0.0f) + "\n";
            Files.writeString(
                outputDir.resolve("mirror-axis-roundtrip.txt"),
                report,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception failure) {
            context.logger().error("Mesh mirror-axis probe roundtrip failed", failure);
        }
    }

    private void dumpState(final String status) {
        if (outputDir == null) return;
        try {
            Files.createDirectories(outputDir);
            final StringBuilder report = new StringBuilder()
                .append("status=").append(status).append('\n')
                .append("time=").append(Instant.now()).append('\n')
                .append("angleDegrees=").append(context.meshMirrorAxis().currentAngleDegrees()).append('\n')
                .append("meshes=").append(context.cubismRead().meshes().size()).append('\n')
                .append("deformers=").append(context.cubismRead().deformers().size()).append('\n');
            Files.writeString(
                outputDir.resolve("mirror-axis-state.txt"),
                report.toString(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception failure) {
            context.logger().error("Mesh mirror-axis probe state dump failed", failure);
        }
    }
}
