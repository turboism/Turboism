package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.startup.StartupSuppressionInstaller;
import dev.turboism.adapter.jdk.PipeImplLoopbackInstaller;
import dev.turboism.runtime.log.RuntimeDiagnostics;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * JVM-level shims that are not declared in the hook manifest: startup
 * suppression (which also carries the runtime startup policy verdicts such as
 * safe-mode) and the PipeImpl loopback shim. Owned outside the registry because
 * their lifecycle spans agent attach rather than a hook phase.
 */
final class JvmShims {

    private static final AtomicReference<StartupSuppressionInstaller.Installation>
        STARTUP_SUPPRESSION = new AtomicReference<>();
    private static final AtomicReference<PipeImplLoopbackInstaller.Installation>
        PIPE_IMPL_SHIM = new AtomicReference<>();

    private JvmShims() {
    }

    static void install(
        final StartupSuppressionInstaller.AttachmentMode attachmentMode,
        final Instrumentation instrumentation,
        final AgentOptions options
    ) {
        final StartupSuppressionInstaller.Installation startupSuppression =
            StartupSuppressionInstaller.install(
                attachmentMode,
                instrumentation,
                options.home(),
                System.getProperty("java.class.path", ""),
                Path.of(System.getProperty("user.dir", ".")),
                code -> RuntimeDiagnostics.debug("bootstrap", "Startup suppression: " + code)
            );
        if (!STARTUP_SUPPRESSION.compareAndSet(null, startupSuppression)) {
            startupSuppression.close();
        }
        RuntimeDiagnostics.debug(
            "bootstrap",
            "Startup suppression status=" + startupSuppression.status()
                + ", safeMode=" + startupSuppression.policy().safeMode()
        );
        final PipeImplLoopbackInstaller.Installation pipeImplShim =
            PipeImplLoopbackInstaller.install(
                instrumentation,
                code -> RuntimeDiagnostics.debug("bootstrap", "Pipe shim: " + code)
            );
        if (!PIPE_IMPL_SHIM.compareAndSet(null, pipeImplShim)) {
            pipeImplShim.close();
        }
        RuntimeDiagnostics.debug(
            "bootstrap",
            "Pipe shim status=" + pipeImplShim.status()
                + ", transformOutcome=" + pipeImplShim.transformOutcome()
        );
    }

    static boolean safeModeActive() {
        final StartupSuppressionInstaller.Installation suppression = STARTUP_SUPPRESSION.get();
        return suppression != null && suppression.policy().safeMode();
    }

    static void closeAll(final Consumer<String> warn) {
        close(STARTUP_SUPPRESSION.getAndSet(null), warn, "startup suppression");
        close(PIPE_IMPL_SHIM.getAndSet(null), warn, "pipe shim");
    }

    private static void close(
        final AutoCloseable installation,
        final Consumer<String> warn,
        final String name
    ) {
        if (installation == null) {
            return;
        }
        try {
            installation.close();
        } catch (Throwable failure) {
            warn.accept("Turboism " + name + " cleanup failed safely");
        }
    }
}
