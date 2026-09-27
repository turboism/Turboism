package dev.turboism.adapter.cubism.textureatlas.image;

import java.lang.instrument.ClassFileTransformer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import dev.turboism.bootstrap.tilebbox.AtlasTileBboxDelegate;

/**
 * Exact-selector transformer for the host's private per-image alpha workaround.
 *
 * <p>It rewrites only the reviewed target classes in {@link AtlasTileBboxTarget#REVIEWED} —
 * {@code com/live2d/util/f/g} on 5.3.x, {@code com/live2d/util/e/g} on 5.2.03 — and only when
 * the observed bytes match that target's pinned SHA-256 (or a bounded extra admitted digest)
 * and the method shape passes {@link AtlasTileBboxPatcher}'s anchor gate. Every other class,
 * and every unrecognised shape, is returned untouched. The transformer is non-retransforming,
 * so it can only act on the first definition — a class the host has already loaded is never
 * patched behind its back.</p>
 */
public final class AtlasTileBboxTransformer implements ClassFileTransformer {

    /**
     * Optional extra admitted digests, for runs where another reviewed instrument (the T039
     * shadow weave) transforms the same class first and changes its bytes. A comma-separated
     * list of at most {@value #MAX_EXTRA_ADMITS} lowercase 64-hex digests; absent by default,
     * so production installs admit only each target's reviewed digest.
     */
    static final String ADMIT_CLASS_SHA256_PROPERTY =
        "turboism.atlasTileBbox.admitClassSha256";
    private static final int MAX_EXTRA_ADMITS = 8;

    /** What the transformer concluded, for diagnostics and tests. */
    public enum Outcome {
        /** No target has been defined yet. */
        NONE,
        /** A target was defined with admitted bytes and was patched. */
        PATCHED,
        /** A target was defined but its bytes matched no admitted digest. */
        HASH_MISMATCH,
        /** A target was defined with admitted bytes but an unexpected shape. */
        SHAPE_REJECTED,
        /** The reviewed shape matched but a host helper the delegate needs was missing. */
        HELPERS_UNAVAILABLE
    }

    private final java.util.Map<String, AtlasTileBboxTarget> targets;
    private final java.util.Set<String> admittedSha256;
    private final AtomicReference<Outcome> outcome = new AtomicReference<>(Outcome.NONE);
    private final AtomicReference<String> diagnostic = new AtomicReference<>("");

    public AtlasTileBboxTransformer() {
        this(java.util.Arrays.asList(AtlasTileBboxTarget.REVIEWED), extraAdmittedDigests());
    }

    AtlasTileBboxTransformer(final AtlasTileBboxTarget target,
                             final java.util.Set<String> admittedSha256) {
        this(java.util.List.of(Objects.requireNonNull(target, "target")), admittedSha256);
    }

    AtlasTileBboxTransformer(final java.util.Collection<AtlasTileBboxTarget> targets,
                             final java.util.Set<String> admittedSha256) {
        final java.util.Map<String, AtlasTileBboxTarget> byName = new java.util.HashMap<>();
        for (final AtlasTileBboxTarget target : targets) {
            byName.put(Objects.requireNonNull(target, "target").internalName(), target);
        }
        this.targets = java.util.Map.copyOf(byName);
        this.admittedSha256 = java.util.Set.copyOf(
            Objects.requireNonNull(admittedSha256, "admittedSha256"));
    }

    /** The reviewed digests plus any bounded extras admitted via the system property. */
    private static java.util.Set<String> extraAdmittedDigests() {
        final String raw;
        try {
            raw = System.getProperty(ADMIT_CLASS_SHA256_PROPERTY, "");
        } catch (RuntimeException unavailable) {
            return java.util.Set.of();
        }
        return parseDigestList(raw);
    }

    /** Comma-separated lowercase 64-hex digests, bounded; malformed entries are dropped. */
    static java.util.Set<String> parseDigestList(final String raw) {
        return dev.turboism.adapter.cubism.textureatlas.AtlasAdmitDigests.parse(
            raw, MAX_EXTRA_ADMITS);
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
    public byte[] transform(final ClassLoader loader, final String className,
                            final Class<?> classBeingRedefined, final ProtectionDomain domain,
                            final byte[] classfileBuffer) {
        return transform(null, className, classBeingRedefined, domain, classfileBuffer);
    }

    @Override
    public byte[] transform(final Module module, final ClassLoader loader, final String className,
                            final Class<?> classBeingRedefined, final ProtectionDomain domain,
                            final byte[] classfileBuffer) {
        final AtlasTileBboxTarget target = targets.get(className);
        if (classfileBuffer == null || target == null) return null;
        final String observed = sha256(classfileBuffer);
        if (!observed.equals(target.reviewedSha256()) && !admittedSha256.contains(observed)) {
            if (outcome.compareAndSet(Outcome.NONE, Outcome.HASH_MISMATCH)) {
                diagnostic.compareAndSet("",
                    target.internalName() + " observed=" + observed);
                report(Outcome.HASH_MISMATCH, target, "observed=" + observed);
            }
            return null;
        }
        try {
            final byte[] patched =
                AtlasTileBboxPatcher.patch(classfileBuffer, target.internalName());
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
     * One stderr line per terminal outcome. RuntimeDiagnostics has no sink during premain, so
     * the console is the only place the transform verdict survives on a real host.
     */
    private static void report(final Outcome outcome, final AtlasTileBboxTarget target,
                               final String detail) {
        try {
            System.err.println("[turboism] atlas-tile-bbox " + outcome
                + " " + target.internalName()
                + (detail == null || detail.isEmpty() ? "" : " " + detail));
        } catch (Throwable ignored) {
            // The console is evidence, never a failure source.
        }
    }

    static String sha256(final byte[] bytes) {
        try {
            final byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            final StringBuilder text = new StringBuilder(hash.length * 2);
            for (final byte value : hash) {
                text.append(Character.forDigit((value >> 4) & 0xF, 16));
                text.append(Character.forDigit(value & 0xF, 16));
            }
            return text.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
