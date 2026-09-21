package dev.turboism.adapter.cubism.textureatlas.cache;

import java.lang.instrument.ClassFileTransformer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import dev.turboism.bootstrap.atlascache.AtlasCacheReuseDelegate;

/**
 * Exact-selector transformer for {@code CTextureAtlas}'s redundant-rebuild guard.
 *
 * <p>Rewrites only the reviewed target in {@link AtlasCacheReuseTarget#REVIEWED}, only when
 * the observed bytes match its pinned SHA-256 (or a bounded extra admitted digest) and the
 * method shape passes {@link AtlasCacheReusePatcher}'s anchor gate. Every other class, and
 * every unrecognised shape, is returned untouched. The transformer is non-retransforming,
 * so it can only act on the first definition — a class the host has already loaded is never
 * patched behind its back.</p>
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

    /**
     * Optional extra admitted digests, for runs where another reviewed instrument
     * transforms the same class first and changes its bytes. A comma-separated list of at
     * most {@value #MAX_EXTRA_ADMITS} lowercase 64-hex digests; absent by default.
     */
    static final String ADMIT_CLASS_SHA256_PROPERTY =
        "turboism.atlasCacheReuse.admitClassSha256";
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
        SHAPE_REJECTED
    }

    private final java.util.Map<String, AtlasCacheReuseTarget> targets;
    private final java.util.Set<String> admittedSha256;
    private final AtomicReference<Outcome> outcome = new AtomicReference<>(Outcome.NONE);
    private final AtomicReference<String> diagnostic = new AtomicReference<>("");

    public AtlasCacheReuseTransformer() {
        this(java.util.Arrays.asList(AtlasCacheReuseTarget.REVIEWED), extraAdmittedDigests());
    }

    AtlasCacheReuseTransformer(final AtlasCacheReuseTarget target,
                               final java.util.Set<String> admittedSha256) {
        this(java.util.List.of(Objects.requireNonNull(target, "target")), admittedSha256);
    }

    AtlasCacheReuseTransformer(final java.util.Collection<AtlasCacheReuseTarget> targets,
                               final java.util.Set<String> admittedSha256) {
        final java.util.Map<String, AtlasCacheReuseTarget> byName = new java.util.HashMap<>();
        for (final AtlasCacheReuseTarget target : targets) {
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
        final AtlasCacheReuseTarget target = targets.get(className);
        if (target == null) return null;
        // Unconditional sighting line: the host defines this class under more than one
        // loader and a silently-skipped definition is indistinguishable from "the patch
        // never ran" in captured console evidence. One bounded line per transform call.
        report("SEEN", target, "loader=" + String.valueOf(loader)
            + " retransform=" + (classBeingRedefined != null)
            + " bytes=" + (classfileBuffer == null ? -1 : classfileBuffer.length)
            + " inSha=" + (classfileBuffer == null ? "null"
                : Integer.toHexString(java.util.Arrays.hashCode(classfileBuffer))));
        // Bounded caller stack: the host may define the same class through more than one
        // path, and only the loading call stack says which code triggered each sighting.
        if (evidenceFile() != null) {
            try {
                final java.io.StringWriter sink = new java.io.StringWriter();
                new Throwable("atlas-cache-reuse define-stack").printStackTrace(
                    new java.io.PrintWriter(sink));
                java.nio.file.Files.writeString(
                    java.nio.file.Path.of(evidenceFile()),
                    sink + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            } catch (Throwable ignored) {
                // Diagnostics only.
            }
        }
        if (classfileBuffer == null) return null;
        final String observed = sha256(classfileBuffer);
        if (!observed.equals(target.reviewedSha256()) && !admittedSha256.contains(observed)) {
            // Every sighting of a reviewed-name class is reported — the host can define
            // the same name under more than one loader, and a silently-skipped definition
            // is indistinguishable from "the patch never ran" in captured evidence.
            report("HASH_MISMATCH", target,
                "observed=" + observed + " loader=" + String.valueOf(loader)
                    + " retransform=" + (classBeingRedefined != null));
            if (outcome.compareAndSet(Outcome.NONE, Outcome.HASH_MISMATCH)) {
                diagnostic.compareAndSet("",
                    target.internalName() + " observed=" + observed);
            }
            return null;
        }
        try {
            final byte[] patched = AtlasCacheReusePatcher.patch(classfileBuffer);
            // No host-member verification here — it would load classes mid-transform.
            // The delegate binds and verifies every handle lazily at first call and
            // declines reuse whenever a member is missing.
            outcome.set(Outcome.PATCHED);
            diagnostic.set(target.internalName() + " " + target.hostLabel());
            report("PATCHED", target,
                "loader=" + String.valueOf(loader)
                    + " retransform=" + (classBeingRedefined != null));
            return patched;
        } catch (AtlasCacheReusePatcher.NotApplicable rejected) {
            outcome.set(Outcome.SHAPE_REJECTED);
            diagnostic.set(target.internalName() + " " + rejected.getMessage());
            report("SHAPE_REJECTED", target, rejected.getMessage());
            return null;
        }
    }

    /**
     * One bounded line per terminal outcome, to stderr and — when
     * {@code turboism.atlasCacheReuse.evidenceFile} names a path — to that file. The
     * host's wrapped stderr stream is asynchronous and can drop or reorder lines under
     * startup load, so validation evidence must not depend on it alone.
     */
    private static void report(final String outcome, final AtlasCacheReuseTarget target,
                               final String detail) {
        final String line = "[turboism] atlas-cache-reuse " + outcome
            + " " + target.internalName()
            + (detail == null || detail.isEmpty() ? "" : " " + detail);
        try {
            System.err.println(line);
        } catch (Throwable ignored) {
            // The console is evidence, never a failure source.
        }
        final String evidence = evidenceFile();
        if (evidence != null && !evidence.isEmpty()) {
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of(evidence),
                    line + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            } catch (Throwable ignored) {
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
