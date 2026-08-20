package dev.turboism.adapter.jdk;

import java.lang.instrument.Instrumentation;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Unconditional premain-only admission and lifecycle for the
 * {@code sun.nio.ch.PipeImpl} AF_INET loopback shim. Mirrors
 * {@code StartupSuppressionInstaller} (install / close / status / diagnostic
 * reporting) but carries no policy: the transform is behaviorally equivalent
 * to pre-JEP-380 JDKs on every platform and Turboism has zero AF_UNIX usage,
 * so the shim is installed unconditionally when the JVM is still starting.
 */
public final class PipeImplLoopbackInstaller {

    private PipeImplLoopbackInstaller() {
    }

    /**
     * Installs the loopback shim transformer, unconditionally, for a JVM that is still starting.
     *
     * <p>Declines rather than fails when {@code sun.nio.ch.PipeImpl} has already been loaded -
     * transformation would come too late - and treats an error while enumerating loaded classes
     * as "already loaded" so the shim is never installed on uncertain ground. The transformer
     * removes itself once the target class has been seen. Every outcome is reported to the
     * diagnostic sink as a stable code; a diagnostic sink that throws is ignored so it cannot
     * block agent startup.
     *
     * @param instrumentation the premain instrumentation to register the transformer on; must not
     *                        be null
     * @param diagnostic receives one stable outcome code; must not be null, may throw harmlessly
     * @return the installation handle, whose {@link Installation#status()} distinguishes
     *         installed from declined and failed; closing it is a no-op unless it installed
     * @throws NullPointerException if either argument is null
     */
    public static Installation install(
        final Instrumentation instrumentation,
        final Consumer<String> diagnostic
    ) {
        Objects.requireNonNull(instrumentation, "instrumentation");
        Objects.requireNonNull(diagnostic, "diagnostic");

        if (targetAlreadyLoaded(instrumentation)) {
            report(diagnostic, "PIPE_IMPL_SHIM_TARGET_ALREADY_LOADED");
            return Installation.completed(Status.TARGET_ALREADY_LOADED);
        }

        final AtomicReference<PipeImplLoopbackClassFileTransformer> reference =
            new AtomicReference<>();
        final PipeImplLoopbackClassFileTransformer transformer =
            new PipeImplLoopbackClassFileTransformer(
                ignored -> {
                    final PipeImplLoopbackClassFileTransformer installed = reference.get();
                    if (installed != null) {
                        instrumentation.removeTransformer(installed);
                    }
                },
                diagnostic
            );
        reference.set(transformer);
        try {
            instrumentation.addTransformer(transformer, false);
            report(diagnostic, "PIPE_IMPL_SHIM_INSTALLED");
            return Installation.installed(instrumentation, transformer);
        } catch (RuntimeException failure) {
            instrumentation.removeTransformer(transformer);
            report(diagnostic, "PIPE_IMPL_SHIM_INSTALL_FAILED");
            return Installation.completed(Status.INSTALL_FAILED);
        }
    }

    private static boolean targetAlreadyLoaded(final Instrumentation instrumentation) {
        try {
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (loaded.getName().equals("sun.nio.ch.PipeImpl")) {
                    return true;
                }
            }
        } catch (RuntimeException failure) {
            return true;
        }
        return false;
    }

    private static void report(final Consumer<String> diagnostic, final String code) {
        try {
            diagnostic.accept(code);
        } catch (RuntimeException ignored) {
            // Diagnostics must not block agent startup.
        }
    }

    public enum Status {
        TARGET_ALREADY_LOADED,
        INSTALLED,
        INSTALL_FAILED
    }

    public static final class Installation implements AutoCloseable {
        private final Status status;
        private final Instrumentation instrumentation;
        private final PipeImplLoopbackClassFileTransformer transformer;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Installation(
            final Status status,
            final Instrumentation instrumentation,
            final PipeImplLoopbackClassFileTransformer transformer
        ) {
            this.status = Objects.requireNonNull(status, "status");
            this.instrumentation = instrumentation;
            this.transformer = transformer;
        }

        private static Installation completed(final Status status) {
            return new Installation(status, null, null);
        }

        private static Installation installed(
            final Instrumentation instrumentation,
            final PipeImplLoopbackClassFileTransformer transformer
        ) {
            return new Installation(Status.INSTALLED, instrumentation, transformer);
        }

        /**
         * @return whether the shim was installed, declined because the target class was already
         *         loaded, or failed while registering the transformer
         */
        public Status status() {
            return status;
        }

        /**
         * Reports what the transformer actually did to the target class, for diagnostics after the
         * fact.
         *
         * @return the transformer's outcome name, or {@code "NOT_INSTALLED"} when no transformer was
         *         ever registered
         */
        public String transformOutcome() {
            return transformer == null ? "NOT_INSTALLED" : transformer.outcome().name();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true) && transformer != null) {
                instrumentation.removeTransformer(transformer);
            }
        }
    }
}
