package dev.turboism.adapter.cubism.textureatlas.image;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exact-selector transformer for the host's private per-image alpha workaround.
 *
 * <p>It rewrites only the reviewed target classes in {@link AtlasTileBboxTarget#REVIEWED} —
 * {@code com/live2d/util/f/g} on 5.3.x, {@code com/live2d/util/e/g} on 5.2.03 — and only when
 * the method shape passes {@link AtlasTileBboxPatcher}'s anchor gate. Every other class, and
 * every unrecognised shape, is returned untouched. The transformer is non-retransforming, so
 * it can only act on the first definition — a class the host has already loaded is never
 * patched behind its back.</p>
 */
public final class AtlasTileBboxTransformer implements ClassFileTransformer {

    /** What the transformer concluded, for diagnostics and tests. */
    public enum Outcome {
        /** No target has been defined yet. */
        NONE,
        /** A target was defined with admitted bytes and was patched. */
        PATCHED,
        /** A target was defined but its method shape differed from the reviewed structure. */
        SHAPE_MISMATCH,
        /** A target was defined with admitted bytes but an unexpected shape. */
        SHAPE_REJECTED,
        /** The reviewed shape matched but a host helper the delegate needs was missing. */
        HELPERS_UNAVAILABLE
    }

    private final java.util.Map<String, java.util.List<AtlasTileBboxTarget>> targets;
    private final AtomicReference<Outcome> outcome = new AtomicReference<>(Outcome.NONE);
    private final AtomicReference<String> diagnostic = new AtomicReference<>("");

    public AtlasTileBboxTransformer() {
        this(java.util.Arrays.asList(AtlasTileBboxTarget.REVIEWED));
    }

    AtlasTileBboxTransformer(final AtlasTileBboxTarget target) {
        this(java.util.List.of(Objects.requireNonNull(target, "target")));
    }

    AtlasTileBboxTransformer(final java.util.Collection<AtlasTileBboxTarget> targets) {
        final java.util.Map<String, java.util.List<AtlasTileBboxTarget>> byName = new java.util.HashMap<>();
        for (final AtlasTileBboxTarget target : targets) {
            Objects.requireNonNull(target, "target");
            byName.computeIfAbsent(target.internalName(), ignored -> new java.util.ArrayList<>())
                    .add(target);
        }
        final java.util.Map<String, java.util.List<AtlasTileBboxTarget>> copy = new java.util.HashMap<>();
        byName.forEach((k, v) -> copy.put(k, java.util.List.copyOf(v)));
        this.targets = java.util.Map.copyOf(copy);
    }

    /** Latest observed outcome; {@code NONE} until a target class has been defined. */
    public Outcome outcome() {
        return outcome.get();
    }

    /** Human-readable detail for a non-clean outcome, or the empty string. */
    public String diagnostic() {
        return diagnostic.get();
    }

    @Override
    public byte[] transform(
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain domain,
            final byte[] classfileBuffer) {
        return transform(null, className, classBeingRedefined, domain, classfileBuffer);
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain domain,
            final byte[] classfileBuffer) {
        final java.util.List<AtlasTileBboxTarget> candidates = targets.get(className);
        if (classfileBuffer == null || candidates == null || candidates.isEmpty()) return null;
        final AtlasTileBboxTarget target = candidates.get(0);
        try {
            final byte[] patched = AtlasTileBboxPatcher.patch(classfileBuffer, target.internalName());
            // The delegate is bootstrap-loaded and reaches the jp.noids.* helpers
            // reflectively through the patched class's loader. Prove all ten handles
            // resolve before committing the patched bytes: a missing helper can only
            // decline the patch here, never fail inside a draw call.
            final String helpers = AtlasTileBboxDelegate.verifyHostAccess(loader);
            if (helpers != null) {
                outcome.set(Outcome.HELPERS_UNAVAILABLE);
                diagnostic.set(target.internalName() + " " + helpers);
                report(Outcome.HELPERS_UNAVAILABLE, target, helpers);
                return null;
            }
            outcome.set(Outcome.PATCHED);
            diagnostic.set(target.internalName() + " " + target.hostLabel());
            report(Outcome.PATCHED, target, "");
            return patched;
        } catch (AtlasTileBboxPatcher.NotApplicable rejected) {
            outcome.set(Outcome.SHAPE_REJECTED);
            diagnostic.set(target.internalName() + " " + rejected.getMessage());
            report(Outcome.SHAPE_REJECTED, target, rejected.getMessage());
            return null;
        }
    }

    /**
     * Keeps transform verdicts in internal debug diagnostics, including buffered premain records.
     * Never writes to the host console, where Cubism labels stderr as errors.
     */
    private static void report(final Outcome outcome, final AtlasTileBboxTarget target, final String detail) {
        try {
            RuntimeDiagnostics.debug(
                    "atlas-tile-bbox",
                    outcome + " " + target.internalName() + (detail == null || detail.isEmpty() ? "" : " " + detail));
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            // Diagnostics must never affect the transform outcome.
        }
    }
}
