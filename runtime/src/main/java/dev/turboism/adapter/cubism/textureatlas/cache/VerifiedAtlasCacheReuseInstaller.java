package dev.turboism.adapter.cubism.textureatlas.cache;

import java.lang.instrument.Instrumentation;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Premain-only admission and lifecycle for the atlas cache-reuse guard.
 *
 * <p>Declines rather than fails when {@code CTextureAtlas} has already been loaded —
 * transformation would come too late — and treats an error while enumerating loaded
 * classes as "already loaded". Closing the installation removes the transformer, which
 * leaves the host class exactly as it was defined; a transform already applied to a
 * defined class cannot be undone, and {@link Status} says so.</p>
 */
public final class VerifiedAtlasCacheReuseInstaller {

    private static final java.util.Set<String> TARGET_CLASS_NAMES = targetClassNames();

    private VerifiedAtlasCacheReuseInstaller() {
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
        private final AtlasCacheReuseTransformer transformer;
        private final AtomicReference<Status> current;

        private Installation(final Status status, final Instrumentation instrumentation,
                            final AtlasCacheReuseTransformer transformer) {
            this.status = status;
            this.instrumentation = instrumentation;
            this.transformer = transformer;
            this.current = new AtomicReference<>(status);
        }

        static Installation installed(final Instrumentation instrumentation,
                                     final AtlasCacheReuseTransformer transformer) {
            return new Installation(Status.INSTALLED, instrumentation, transformer);
        }

        static Installation declined(final Status status) {
            return new Installation(status, null, null);
        }

        /** Current lifecycle status; becomes {@link Status#CLOSED} after {@link #close()}. */
        public Status status() {
            return current.get();
        }

        /** Outcome of the transform itself, only meaningful once the target was defined. */
        public AtlasCacheReuseTransformer.Outcome transformOutcome() {
            return transformer == null
                ? AtlasCacheReuseTransformer.Outcome.NONE
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
     * Installs the transformer for a JVM that is still starting.
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
            report(diagnostic, "ATLAS_CACHE_REUSE_TARGET_ALREADY_LOADED");
            return Installation.declined(Status.TARGET_ALREADY_LOADED);
        }

        final AtlasCacheReuseTransformer transformer = new AtlasCacheReuseTransformer();
        try {
            instrumentation.addTransformer(transformer, false);
            report(diagnostic, "ATLAS_CACHE_REUSE_INSTALLED");
            return Installation.installed(instrumentation, transformer);
        } catch (RuntimeException failure) {
            report(diagnostic, "ATLAS_CACHE_REUSE_INSTALL_FAILED");
            return Installation.declined(Status.INSTALL_FAILED);
        }
    }

    private static boolean targetAlreadyLoaded(final Instrumentation instrumentation) {
        try {
            for (final Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (TARGET_CLASS_NAMES.contains(loaded.getName())) return true;
            }
            return false;
        } catch (RuntimeException uncertain) {
            return true;
        }
    }

    private static java.util.Set<String> targetClassNames() {
        final java.util.Set<String> names = new java.util.HashSet<>();
        for (final AtlasCacheReuseTarget target : AtlasCacheReuseTarget.REVIEWED) {
            names.add(target.className());
        }
        return java.util.Set.copyOf(names);
    }

    private static void report(final Consumer<String> diagnostic, final String code) {
        try {
            diagnostic.accept(code);
        } catch (RuntimeException ignored) {
            // A diagnostic sink must never be able to block agent startup.
        }
    }
}
