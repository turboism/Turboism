package dev.turboism.adapter.cubism.textureatlas.cache;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exact-selector transformer for {@code CTextureAtlas}'s redundant-rebuild guard.
 *
 * <p>Rewrites only the reviewed target in {@link AtlasCacheReuseTarget#REVIEWED}, and only
 * when the method shape passes {@link AtlasCacheReusePatcher}'s anchor gate. Every other
 * class, and every unrecognised shape, is returned untouched. The transformer is
 * non-retransforming, so it can only act on the first definition — a class the host has
 * already loaded is never patched behind its back.</p>
 *
 * <p>Invariant: {@link #transform} must never load or reflect on classes. Resolving a host
 * class here — even a side class such as {@code ModelImageEntry}, whose synthetic
 * constructor signature references {@code CTextureAtlas} — can recursively define the very
 * class being transformed; that nested definition bypasses this transformer, wins loader
 * registration as the live unpatched class, and kills the patched definition on a
 * duplicate-class {@code LinkageError} that then fails host admission. Member verification
 * happens lazily inside {@link AtlasCacheReuseDelegate} at the first {@code updateTexture}
 * call, when the patched class is already fully defined.</p>
 */
public final class AtlasCacheReuseTransformer implements ClassFileTransformer {

    /** What the transformer concluded, for diagnostics and tests. */
    public enum Outcome {
        /** No target has been defined yet. */
        NONE,
        /** A target was defined with admitted bytes and was patched. */
        PATCHED,
        /** A target was defined but its method shape differed from the reviewed structure. */
        SHAPE_MISMATCH,
        /** A target was defined with admitted bytes but an unexpected shape. */
        SHAPE_REJECTED
    }

    private final java.util.Map<String, java.util.List<AtlasCacheReuseTarget>> targets;
    private final AtomicReference<Outcome> outcome = new AtomicReference<>(Outcome.NONE);
    private final AtomicReference<String> diagnostic = new AtomicReference<>("");

    public AtlasCacheReuseTransformer() {
        this(java.util.Arrays.asList(AtlasCacheReuseTarget.REVIEWED));
    }

    AtlasCacheReuseTransformer(final AtlasCacheReuseTarget target) {
        this(java.util.List.of(Objects.requireNonNull(target, "target")));
    }

    AtlasCacheReuseTransformer(final java.util.Collection<AtlasCacheReuseTarget> targets) {
        final java.util.Map<String, java.util.List<AtlasCacheReuseTarget>> byName = new java.util.HashMap<>();
        for (final AtlasCacheReuseTarget target : targets) {
            Objects.requireNonNull(target, "target");
            byName.computeIfAbsent(target.internalName(), ignored -> new java.util.ArrayList<>())
                    .add(target);
        }
        final java.util.Map<String, java.util.List<AtlasCacheReuseTarget>> copy = new java.util.HashMap<>();
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
        final java.util.List<AtlasCacheReuseTarget> candidates = targets.get(className);
        if (candidates == null || candidates.isEmpty()) return null;
        final AtlasCacheReuseTarget first = candidates.get(0);
        // Validation-only sighting: production stays silent. The host can define this
        // class under several loaders, so opted-in evidence records every sighting.
        report(
                "SEEN",
                first,
                "loader=" + String.valueOf(loader)
                        + " retransform=" + (classBeingRedefined != null)
                        + " bytes=" + (classfileBuffer == null ? -1 : classfileBuffer.length)
                        + " inSha="
                        + (classfileBuffer == null
                                ? "null"
                                : Integer.toHexString(java.util.Arrays.hashCode(classfileBuffer))));
        // Bounded caller stack: the host may define the same class through more than one
        // path, and only the loading call stack says which code triggered each sighting.
        if (evidenceFile() != null) {
            try {
                final java.io.StringWriter sink = new java.io.StringWriter();
                new Throwable("atlas-cache-reuse define-stack").printStackTrace(new java.io.PrintWriter(sink));
                java.nio.file.Files.writeString(
                        java.nio.file.Path.of(evidenceFile()),
                        sink + System.lineSeparator(),
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (Throwable ignored) {
                FatalErrors.rethrowIfFatal(ignored);
                // Diagnostics only.
            }
        }
        if (classfileBuffer == null) return null;
        final AtlasCacheReuseTarget target = first;
        try {
            final byte[] patched = AtlasCacheReusePatcher.patch(classfileBuffer);
            // No host-member verification here — it would load classes mid-transform.
            // The delegate binds and verifies every handle lazily at first call and
            // declines reuse whenever a member is missing.
            outcome.set(Outcome.PATCHED);
            diagnostic.set(target.internalName() + " " + target.hostLabel());
            report(
                    "PATCHED",
                    target,
                    "loader=" + String.valueOf(loader) + " retransform=" + (classBeingRedefined != null));
            return patched;
        } catch (AtlasCacheReusePatcher.NotApplicable rejected) {
            outcome.set(Outcome.SHAPE_REJECTED);
            diagnostic.set(target.internalName() + " " + rejected.getMessage());
            report("SHAPE_REJECTED", target, rejected.getMessage());
            return null;
        }
    }

    /**
     * Appends validation diagnostics only when {@code turboism.atlasCacheReuse.evidenceFile}
     * names a path. Never writes to the host console: Cubism labels stderr as errors,
     * including successful patch outcomes.
     */
    private static void report(final String outcome, final AtlasCacheReuseTarget target, final String detail) {
        final String line = "[turboism] atlas-cache-reuse " + outcome
                + " " + target.internalName()
                + (detail == null || detail.isEmpty() ? "" : " " + detail);
        final String evidence = evidenceFile();
        if (evidence != null && !evidence.isEmpty()) {
            try {
                java.nio.file.Files.writeString(
                        java.nio.file.Path.of(evidence),
                        line + System.lineSeparator(),
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (Throwable ignored) {
                FatalErrors.rethrowIfFatal(ignored);
                // Evidence capture must never affect the transform outcome.
            }
        }
    }

    /** The validation evidence path, or {@code null} when the property is unset. */
    private static String evidenceFile() {
        try {
            return System.getProperty("turboism.atlasCacheReuse.evidenceFile");
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
