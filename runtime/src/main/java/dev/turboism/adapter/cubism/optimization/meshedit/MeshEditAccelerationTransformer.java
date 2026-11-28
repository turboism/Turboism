package dev.turboism.adapter.cubism.optimization.meshedit;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coordinates fail-closed premain bytecode acceleration for Cubism interactive mesh-edit
 * geometry, overlay rendering, soft selection, triangulation, and topology lookups.
 */
public final class MeshEditAccelerationTransformer implements ClassFileTransformer {

    /** Transformation disposition recorded per target host class. */
    public enum Outcome {
        /** Transformer has not observed any supported host class yet. */
        NOT_APPLICABLE,
        /** Target class matched and was rewritten successfully. */
        PATCHED,
        /** Target class failed structural verification; host bytes remained unchanged. */
        SHAPE_REJECTED
    }

    private final AtomicReference<Outcome> lastOutcome = new AtomicReference<>(Outcome.NOT_APPLICABLE);
    private final AtomicReference<String> lastDiagnostic = new AtomicReference<>("");
    private final Map<String, Outcome> outcomes = new ConcurrentHashMap<>();
    private final Map<String, String> diagnostics = new ConcurrentHashMap<>();

    /** Constructs the interactive mesh-edit bytecode transformer. */
    public MeshEditAccelerationTransformer() {}

    /**
     * Returns the most recent transformation outcome across supported mesh-edit classes.
     *
     * @return latest recorded {@link Outcome}
     */
    public Outcome outcome() {
        return lastOutcome.get();
    }

    /**
     * Returns the diagnostic recorded with the most recent transformation attempt.
     *
     * @return failure reason or empty string on success
     */
    public String diagnostic() {
        return lastDiagnostic.get();
    }

    /**
     * Returns the outcome recorded for a specific internal host class name.
     *
     * @param internalClassName JVM internal class name such as {@code com/live2d/...}
     * @return recorded {@link Outcome}, or {@link Outcome#NOT_APPLICABLE}
     */
    public Outcome outcomeFor(final String internalClassName) {
        return internalClassName == null
                ? Outcome.NOT_APPLICABLE
                : outcomes.getOrDefault(internalClassName, Outcome.NOT_APPLICABLE);
    }

    /**
     * Returns the diagnostic message recorded for a specific internal host class name.
     *
     * @param internalClassName JVM internal class name such as {@code com/live2d/...}
     * @return failure message or empty string
     */
    public String diagnosticFor(final String internalClassName) {
        return internalClassName == null ? "" : diagnostics.getOrDefault(internalClassName, "");
    }

    @Override
    public byte[] transform(
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer) {
        if (classfileBuffer == null || className == null || classBeingRedefined != null) {
            return null;
        }
        final boolean geometryTarget = MeshEditGeometryPatcher.supports(className);
        final boolean topologyTarget = !geometryTarget && MeshEditTopologyPatcher.supports(className);
        if (!geometryTarget && !topologyTarget) {
            return null;
        }
        try {
            if (MeshEditAsmSupport.isAlreadyInstrumented(MeshEditAsmSupport.newClassReader(classfileBuffer))) {
                return null;
            }
            final byte[] patched = geometryTarget
                    ? MeshEditGeometryPatcher.patch(className, classfileBuffer, loader)
                    : MeshEditTopologyPatcher.patch(className, classfileBuffer, loader);
            if (patched == null) {
                recordFailure(className, "Patcher returned null for " + className);
                return null;
            }
            outcomes.put(className, Outcome.PATCHED);
            diagnostics.put(className, "");
            lastOutcome.set(Outcome.PATCHED);
            lastDiagnostic.set("");
            return patched;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            recordFailure(className, failure.getClass().getSimpleName() + ": " + failure.getMessage());
            return null;
        }
    }

    private void recordFailure(final String className, final String message) {
        final String safeMessage = message == null ? "" : message;
        outcomes.put(className, Outcome.SHAPE_REJECTED);
        diagnostics.put(className, safeMessage);
        lastOutcome.set(Outcome.SHAPE_REJECTED);
        lastDiagnostic.set(safeMessage);
    }
}
