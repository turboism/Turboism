package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadElisionBridge;
import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadElisionTarget;
import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadElisionTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * Exact admission, dependency verification and restoration for the test-only
 * skipped-frame upload elision experiment. Two host classes are rewritten —
 * the float wrapper {@code mesh/a/b} and the integer/index wrapper
 * {@code mesh/a/c}, each inside {@code b(GL2ES2,int)}. The bridge reads
 * {@code a.a.i()}, {@code <wrapper>.b()} and {@code shader/A.a(Buffer)}; every
 * such dependency body is verified against the official artifact before any
 * rewrite. On close each class must hash back to its pre-rewrite SHA-256.
 *
 * <p>Composition: the transform touches only {@code mesh/a/*}; the
 * uniform-location hooks target {@code shader/GShader}, {@code shader/A} and
 * JOGL classes, and the glGetError elision targets {@code shader/A.a(GL,String,Z)}
 * — a different method on a class this experiment does not rewrite, so the
 * dependency check on {@code shader/A.a(Buffer)} still sees the official body
 * under either of those transforms. The two experiments remain disjoint.</p>
 */
final class VerifiedSkippedFrameUploadElisionInstaller implements AutoCloseable {

    /** Operator kill-switch id checked through the startup hook policy. */
    static final String HOOK_ID = "cubism.render.upload-elision";

    private final Instrumentation instrumentation;
    private final SkippedFrameUploadElisionTarget target;
    private final SkippedFrameUploadElisionBridge bridge;
    private final List<Class<?>> entries = new ArrayList<>();
    private final List<SkippedFrameUploadElisionTransformer> transformers = new ArrayList<>();
    private final List<SkippedFrameUploadElisionTransformer> registered = new ArrayList<>();
    private boolean installed, restored;

    /**
     * Production admission: the operator requested the hook, the JVM can
     * retransform, the startup policy did not disable the id, and the host
     * artifact is a reviewed version.
     */
    static boolean admitted(
            final HostArtifactDigest digest,
            final dev.turboism.config.RuntimeStartupConfig config,
            final boolean requested,
            final int jvm) {
        return requested
                && jvm >= 17
                && config.hookEnabled(HOOK_ID)
                && SkippedFrameUploadElisionTarget.of(digest).isPresent();
    }

    VerifiedSkippedFrameUploadElisionInstaller(
            final Instrumentation instrumentation, final Path artifact, final ClassLoader loader) throws Exception {
        this(instrumentation, artifact, loader, false);
    }

    VerifiedSkippedFrameUploadElisionInstaller(
            final Instrumentation instrumentation,
            final Path artifact,
            final ClassLoader loader,
            final boolean production)
            throws Exception {
        target = SkippedFrameUploadElisionTarget.of(HostArtifactDigest.from(artifact))
                .orElseThrow(() -> new IllegalArgumentException("upload elision unsupported host artifact"));
        if (Runtime.version().feature() < 17) {
            throw new IllegalArgumentException("upload elision requires JVM17+");
        }
        this.instrumentation = instrumentation;
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("retransform unavailable");
        }
        try (JarFile jar = new JarFile(artifact.toFile())) {
            for (final String owner : SkippedFrameUploadElisionTarget.OWNERS) {
                final Class<?> type = Class.forName(owner.replace('/', '.'), false, loader);
                VerifiedHookInstallerSupport.attestClassSource(
                        type, loader, artifact, "upload elision dependency loader/source mismatch: " + type.getName());
                entries.add(type);
                transformers.add(new SkippedFrameUploadElisionTransformer(
                        loader, artifact, VerifiedHookInstallerSupport.readReferenceBytes(jar, type), owner));
            }
            verify(jar, loader, artifact, SkippedFrameUploadElisionTarget.BASE_OWNER, "i", "()Ljava/nio/IntBuffer;");
            verify(jar, loader, artifact, SkippedFrameUploadElisionTarget.BASE_OWNER, "k", "()Z");
            for (final String owner : SkippedFrameUploadElisionTarget.OWNERS) {
                verify(jar, loader, artifact, owner, "b", "()Ljava/nio/Buffer;");
            }
            verify(jar, loader, artifact, target.sizeOwner(), "a", "(Ljava/nio/Buffer;)J");
        }
        bridge = new SkippedFrameUploadElisionBridge(loader, production, target.sizeOwner());
    }

    private void verify(
            final JarFile jar,
            final ClassLoader loader,
            final Path artifact,
            final String owner,
            final String method,
            final String descriptor)
            throws Exception {
        final Class<?> type = Class.forName(owner.replace('/', '.'), false, loader);
        VerifiedHookInstallerSupport.attestClassSource(
                type, loader, artifact, "upload elision dependency loader/source mismatch: " + type.getName());
        final byte[] actual = VerifiedHookInstallerSupport.captureLoadedBytes(
                instrumentation,
                type,
                "upload elision class unmodifiable: " + type.getName(),
                "upload elision dependency inspection absent: " + type.getName());
        final List<String> expected = ReviewedMethodShape.read(
                VerifiedHookInstallerSupport.readReferenceBytes(jar, type), owner, method, descriptor);
        if (expected == null || !expected.equals(ReviewedMethodShape.read(actual, owner, method, descriptor))) {
            throw new IllegalStateException("upload elision dependency body mismatch: " + owner + "." + method);
        }
    }

    synchronized void install() throws Exception {
        if (installed) return;
        for (final Class<?> type : entries) {
            if (!instrumentation.isModifiableClass(type)) {
                throw new IllegalStateException("upload elision entry unmodifiable: " + type.getName());
            }
        }
        bridge.install();
        try {
            for (int i = 0; i < entries.size(); i++) {
                final SkippedFrameUploadElisionTransformer transformer = transformers.get(i);
                instrumentation.addTransformer(transformer, true);
                registered.add(transformer);
                instrumentation.retransformClasses(entries.get(i));
                if (transformer.matches() != 1 || transformer.guarded() != 2 || transformer.failure() != null) {
                    throw new IllegalStateException("upload elision entry not admitted: "
                            + entries.get(i).getName() + " " + transformer.failure());
                }
            }
            installed = true;
        } catch (Exception | Error failure) {
            VerifiedHookInstallerSupport.closeOnInstallFailure(failure, this);
            throw failure;
        }
    }

    /** Guarded upload call sites across both rewritten wrappers. */
    int sites() {
        int total = 0;
        for (final SkippedFrameUploadElisionTransformer transformer : transformers) {
            total += transformer.guarded();
        }
        return total;
    }

    @Override
    public synchronized void close() {
        final Map<String, Long> stats = bridge.snapshot();
        bridge.close();
        if (restored) return;
        Throwable failure = null;
        // Detach every registered transformer even when an earlier removal fails.
        for (final var transformer : List.copyOf(registered)) {
            try {
                instrumentation.removeTransformer(transformer);
                registered.remove(transformer);
            } catch (Exception | Error problem) {
                // @containment-exempt: problem is merged and rethrown below with fatal error prioritized
                failure = dev.turboism.core.runtime.work.FatalErrors.mergeForCleanup(failure, problem);
            }
        }
        // A failed restoration is retryable after registration has been undone.
        // beforeSha256 is per class, including successful rewrites before a
        // later entry failed admission; installed is deliberately irrelevant.
        for (int i = 0; i < entries.size(); i++) {
            final String before = transformers.get(i).beforeSha256();
            if (before == null) continue;
            try {
                final String hash =
                        VerifiedHookInstallerSupport.sha256Hex(VerifiedHookInstallerSupport.captureLoadedBytes(
                                instrumentation,
                                entries.get(i),
                                "upload elision class unmodifiable: "
                                        + entries.get(i).getName(),
                                "upload elision dependency inspection absent: "
                                        + entries.get(i).getName()));
                if (!hash.equals(before)) {
                    throw new IllegalStateException("upload elision restoration not proven: "
                            + entries.get(i).getName());
                }
            } catch (Exception | Error problem) {
                // @containment-exempt: problem is merged and rethrown below with fatal error prioritized
                failure = dev.turboism.core.runtime.work.FatalErrors.mergeForCleanup(failure, problem);
            }
        }
        installed = false;
        restored = failure == null;
        dev.turboism.runtime.log.RuntimeDiagnostics.info(
                "bootstrap",
                "TURBOISM_UPLOAD_ELISION closed elided=" + stats.get("elided")
                        + " passed=" + stats.get("passed") + " calls=" + stats.get("calls")
                        + " clears=" + stats.get("clears") + reportTail(stats)
                        + " restored=" + restored);
        if (failure != null) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
            throw new IllegalStateException("upload elision restoration failed", failure);
        }
    }

    /** Pass-reason, per-kind and content-mode counters for the close marker. */
    private static String reportTail(final Map<String, Long> stats) {
        return " contextClears=" + stats.get("contextClears")
                + " nonSkippedClears=" + stats.get("nonSkippedClears")
                + " lifecycleClears=" + stats.get("lifecycleClears")
                + " exceptionClears=" + stats.get("exceptionClears")
                + " observerFailures=" + stats.get("observerFailures")
                + " floatElided=" + stats.get("floatElided")
                + " floatPassed=" + stats.get("floatPassed")
                + " indexElided=" + stats.get("indexElided")
                + " indexPassed=" + stats.get("indexPassed")
                + " passGate=" + stats.get("passGate")
                + " passNoBaseline=" + stats.get("passNoBaseline")
                + " passSize=" + stats.get("passSize")
                + " passBuffer=" + stats.get("passBuffer")
                + " passRegion=" + stats.get("passRegion")
                + " passContent=" + stats.get("passContent")
                + " compares=" + stats.get("compares")
                + " compareNanos=" + stats.get("compareNanos")
                + " contentElided=" + stats.get("contentElided")
                + " snapshotBytes=" + stats.get("snapshotBytes")
                + " snapshotBytesPeak=" + stats.get("snapshotBytesPeak")
                + " entries=" + stats.get("entries")
                + " peakEntries=" + stats.get("peakEntries")
                + " capacity=" + stats.get("capacity")
                + " failedInserts=" + stats.get("failedInserts")
                + " grows=" + stats.get("grows");
    }

    boolean restored() {
        return restored;
    }
}
