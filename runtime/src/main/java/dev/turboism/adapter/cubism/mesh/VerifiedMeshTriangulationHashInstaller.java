package dev.turboism.adapter.cubism.mesh;

import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Premain-only admission and lifecycle for the mesh triangulation hash fix.
 *
 * <p>Declines rather than fails when the target has already been loaded — transformation would come
 * too late — and treats an error while enumerating loaded classes as "already loaded", so the fix is
 * never installed on uncertain ground. Closing the installation removes the transformer, which
 * leaves the host class exactly as it was defined:</p>
 *
 * <p>Removal cannot undo a transform that has already been applied to a defined class, and the
 * installation handle says so through {@link Status}. Callers decide whether to keep the fix for the
 * process lifetime or to start without it.</p>
 */
public final class VerifiedMeshTriangulationHashInstaller {

    private static final String TARGET_CLASS_NAME =
        "com.live2d.graphics3d.editableMesh.triangulation.l";

    private VerifiedMeshTriangulationHashInstaller() {
    }

    /** Lifecycle outcome of one installation attempt. */
    public enum Status {
        INSTALLED,
        TARGET_ALREADY_LOADED,
        INSTALL_FAILED,
        CLOSED
    }

    /** Handle for an installation attempt; closing it is only meaningful when it installed. */
    public static final class Installation implements AutoCloseable {
        private final Status status;
        private final Instrumentation instrumentation;
        private final MeshTriangulationHashTransformer transformer;
        private final AtomicReference<Status> current;

        private Installation(final Status status, final Instrumentation instrumentation,
                            final MeshTriangulationHashTransformer transformer) {
            this.status = status;
            this.instrumentation = instrumentation;
            this.transformer = transformer;
            this.current = new AtomicReference<>(status);
        }

        static Installation installed(final Instrumentation instrumentation,
                                     final MeshTriangulationHashTransformer transformer) {
            return new Installation(Status.INSTALLED, instrumentation, transformer);
        }

        static Installation declined(final Status status) {
            return new Installation(status, null, null);
        }

        /** Current lifecycle status; becomes {@link Status#CLOSED} after {@link #close()}. */
        public Status status() {
            return current.get();
        }

        /** Outcome of the transform itself, which is only meaningful once the target was defined. */
        public MeshTriangulationHashTransformer.Outcome transformOutcome() {
            return transformer == null
                ? MeshTriangulationHashTransformer.Outcome.NONE
                : transformer.outcome();
        }

        /** Transformer diagnostic detail, or the empty string when none was installed. */
        public String diagnostic() {
            return transformer == null ? "" : transformer.diagnostic();
        }

        @Override
        public void close() {
            final Status previous = current.getAndSet(Status.CLOSED);
            if (previous != Status.INSTALLED || instrumentation == null || transformer == null) {
                return;
            }
            try {
                instrumentation.removeTransformer(transformer);
            } catch (RuntimeException ignored) {
                // Removal is best effort: the runtime is shutting down and the JVM owns the rest.
            }
        }
    }

    /**
     * Installs the fix for a JVM that is still starting.
     *
     * @param instrumentation the premain instrumentation to register the transformer on
     * @param diagnostic receives one stable outcome code; exceptions are swallowed
     * @return the installation handle; its status distinguishes installed from declined
     */
    public static Installation install(final Instrumentation instrumentation,
                                       final Consumer<String> diagnostic) {
        Objects.requireNonNull(instrumentation, "instrumentation");
        Objects.requireNonNull(diagnostic, "diagnostic");

        if (targetAlreadyLoaded(instrumentation)) {
            report(diagnostic, "MESH_TRIANGULATION_HASH_TARGET_ALREADY_LOADED");
            return Installation.declined(Status.TARGET_ALREADY_LOADED);
        }

        final MeshTriangulationHashTransformer transformer = new MeshTriangulationHashTransformer();
        try {
            instrumentation.addTransformer(transformer, false);
            report(diagnostic, "MESH_TRIANGULATION_HASH_INSTALLED");
            return Installation.installed(instrumentation, transformer);
        } catch (RuntimeException failure) {
            report(diagnostic, "MESH_TRIANGULATION_HASH_INSTALL_FAILED");
            return Installation.declined(Status.INSTALL_FAILED);
        }
    }

    private static boolean targetAlreadyLoaded(final Instrumentation instrumentation) {
        try {
            for (final Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (TARGET_CLASS_NAME.equals(loaded.getName())) return true;
            }
            return false;
        } catch (RuntimeException uncertain) {
            return true;
        }
    }

    private static void report(final Consumer<String> diagnostic, final String code) {
        try {
            diagnostic.accept(code);
        } catch (RuntimeException ignored) {
            // A diagnostic sink must never be able to block agent startup.
        }
    }

    /** Exposed so the transformer can be exercised directly by tests without an Instrumentation. */
    public static byte[] transformForTesting(final byte[] classFileBuffer,
                                             final ProtectionDomain domain) {
        return new MeshTriangulationHashTransformer()
            .transform(null, TARGET_CLASS_NAME.replace('.', '/'), null, domain, classFileBuffer);
    }
}
