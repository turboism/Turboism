package dev.turboism.adapter.jdk;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Single-shot {@link ClassFileTransformer} for the exact JDK bootstrap class
 * {@code sun/nio/ch/PipeImpl}. Unlike the Cubism startup suppression
 * transformer, the JDK class is defined by the bootstrap loader, so
 * {@code loader == null} is the accepted identity and any other loader is
 * rejected. Always fails open: every rejected or failed shape returns
 * {@code null} (original bytes) and records an outcome; nothing is ever
 * thrown into the class loading path.
 */
final class PipeImplLoopbackClassFileTransformer implements ClassFileTransformer {

    private final PipeImplLoopbackTransformer transformer = new PipeImplLoopbackTransformer();
    private final Consumer<PipeImplLoopbackClassFileTransformer> cleanup;
    private final Consumer<String> diagnostic;
    private final AtomicBoolean targetAttempted = new AtomicBoolean(false);
    private final AtomicReference<Outcome> outcome = new AtomicReference<>(Outcome.PENDING);

    PipeImplLoopbackClassFileTransformer(
        final Consumer<PipeImplLoopbackClassFileTransformer> cleanup,
        final Consumer<String> diagnostic
    ) {
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }

    @Override
    public byte[] transform(
        final Module module,
        final ClassLoader loader,
        final String className,
        final Class<?> classBeingRedefined,
        final ProtectionDomain protectionDomain,
        final byte[] classfileBuffer
    ) throws IllegalClassFormatException {
        if (!PipeImplLoopbackTransformer.TARGET_OWNER.equals(className)
            || targetAttempted.get()) {
            return null;
        }
        if (!targetAttempted.compareAndSet(false, true)) {
            return null;
        }
        try {
            if (classBeingRedefined != null) {
                outcome.set(Outcome.RETRANSFORM_REJECTED);
                return null;
            }
            if (loader != null) {
                outcome.set(Outcome.LOADER_REJECTED);
                return null;
            }
            final byte[] transformed = transformer.transformClass(classfileBuffer);
            outcome.set(Outcome.TRANSFORMED);
            return transformed;
        } catch (PipeImplLoopbackTransformer.TransformationRejectedException failure) {
            outcome.set(Outcome.SHAPE_REJECTED);
            return null;
        } catch (RuntimeException failure) {
            outcome.set(Outcome.TRANSFORMATION_FAILED);
            return null;
        } finally {
            reportOutcome();
            try {
                cleanup.accept(this);
            } catch (RuntimeException ignored) {
                outcome.compareAndSet(Outcome.TRANSFORMED, Outcome.CLEANUP_FAILED);
                report("PIPE_IMPL_SHIM_TRANSFORM_CLEANUP_FAILED");
            }
        }
    }

    Outcome outcome() {
        return outcome.get();
    }

    private void reportOutcome() {
        report("PIPE_IMPL_SHIM_TRANSFORM_" + outcome.get().name());
    }

    private void report(final String code) {
        try {
            diagnostic.accept(code);
        } catch (RuntimeException ignored) {
            // Diagnostics must not affect the class loading path.
        }
    }

    enum Outcome {
        PENDING,
        TRANSFORMED,
        RETRANSFORM_REJECTED,
        LOADER_REJECTED,
        SHAPE_REJECTED,
        TRANSFORMATION_FAILED,
        CLEANUP_FAILED
    }
}
