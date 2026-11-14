package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadElisionBridge;
import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadElisionTarget;
import dev.turboism.adapter.cubism.optimization.uploadelision.SkippedFrameUploadElisionTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 *
 * <p>The protocol itself lives in {@link VerifiedInstall}: one target per wrapper, both retransformed
 * in one call and both re-captured on close. The validation mode never consults the startup policy —
 * the harness owns its gate — while the production mode keeps the kill switch.</p>
 */
final class VerifiedSkippedFrameUploadElisionInstaller implements AutoCloseable {

    /** Operator kill-switch id checked through the startup hook policy. */
    static final String HOOK_ID = "cubism.render.upload-elision";

    /** The admission row this hook froze: policy id, kill-switch semantics and JVM predicate. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);

    /** The validation-mode row: the harness's system property is the only switch. */
    private static final Admission VALIDATION_ADMISSION =
            new Admission(HOOK_ID, PolicyCheck.SKIP, JvmCheck.AT_LEAST_17);

    /** Each wrapper transformer rewrites exactly one site; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_MATCHES = 1;

    /** The close proof re-captures both rewritten wrappers; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_OWNERS = 2;

    private final InstallContext context;
    private final boolean production;
    private SkippedFrameUploadElisionBridge bridge;
    private VerifiedInstall<SkippedFrameUploadElisionTarget> hook;

    VerifiedSkippedFrameUploadElisionInstaller(
            final Instrumentation instrumentation, final Path artifact, final ClassLoader loader) {
        this(InstallContext.of(instrumentation, artifact, loader), false);
    }

    VerifiedSkippedFrameUploadElisionInstaller(final InstallContext context, final boolean production) {
        this.context = context;
        this.production = production;
    }

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
        return VerifiedInstall.admits(
                ADMISSION,
                config,
                requested,
                jvm,
                () -> SkippedFrameUploadElisionTarget.of(digest).isPresent());
    }

    private WeaveSpec<SkippedFrameUploadElisionTarget> spec() {
        return new WeaveSpec<SkippedFrameUploadElisionTarget>(
                production ? ADMISSION : VALIDATION_ADMISSION,
                "upload elision",
                VerifiedSkippedFrameUploadElisionInstaller::resolve,
                new WeavePlan<>(
                        VerifiedSkippedFrameUploadElisionInstaller::targets,
                        VerifiedSkippedFrameUploadElisionInstaller::guardsProven),
                (target, launch) -> {
                    bridge = new SkippedFrameUploadElisionBridge(launch.hostLoader(), production, target.sizeOwner());
                    return BridgeLayer.of(bridge::install, bridge);
                },
                WeaveEffects.none());
    }

    private static Optional<SkippedFrameUploadElisionTarget> resolve(final Path artifact) throws Exception {
        return SkippedFrameUploadElisionTarget.of(HostArtifactDigest.cached(artifact));
    }

    private static List<WeaveTarget> targets(final SkippedFrameUploadElisionTarget target, final WeaveSource source)
            throws Exception {
        final List<WeaveTarget> targets = new ArrayList<>(SkippedFrameUploadElisionTarget.OWNERS.size());
        for (final String owner : SkippedFrameUploadElisionTarget.OWNERS) {
            final Class<?> type = source.load(owner);
            final SkippedFrameUploadElisionTransformer transformer = new SkippedFrameUploadElisionTransformer(
                    source.loader(), source.artifact(), source.referenceBytes(type), owner);
            targets.add(WeaveTarget.single(
                    type, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure));
        }
        verify(source, SkippedFrameUploadElisionTarget.BASE_OWNER, "i", "()Ljava/nio/IntBuffer;");
        verify(source, SkippedFrameUploadElisionTarget.BASE_OWNER, "k", "()Z");
        for (final String owner : SkippedFrameUploadElisionTarget.OWNERS) {
            verify(source, owner, "b", "()Ljava/nio/Buffer;");
        }
        verify(source, target.sizeOwner(), "a", "(Ljava/nio/Buffer;)J");
        return targets;
    }

    private static void verify(
            final WeaveSource source, final String owner, final String method, final String descriptor)
            throws Exception {
        final Class<?> type = source.load(owner);
        final byte[] actual = VerifiedHookInstallerSupport.captureLoadedBytes(
                source.instrumentation(),
                type,
                "upload elision class unmodifiable: " + type.getName(),
                "upload elision dependency inspection absent: " + type.getName());
        final List<String> expected = ReviewedMethodShape.read(source.referenceBytes(type), owner, method, descriptor);
        if (expected == null || !expected.equals(ReviewedMethodShape.read(actual, owner, method, descriptor))) {
            throw new IllegalStateException("upload elision dependency body mismatch: " + owner + "." + method);
        }
    }

    /** Each wrapper must guard exactly two upload call sites, not merely match one method. */
    private static String guardsProven(final List<WeaveTarget> targets) {
        for (final WeaveTarget target : targets) {
            final int guarded = target.transformer(SkippedFrameUploadElisionTransformer.class)
                    .guarded();
            if (guarded != 2) {
                return "guarded=" + guarded + " in " + target.type().getName();
            }
        }
        return null;
    }

    /**
     * Installs the weave through {@link VerifiedInstall}.
     *
     * @throws IllegalStateException when the artifact is not a reviewed upload-elision host
     * @throws Exception when registration, retransformation or the match proof fails
     */
    synchronized void install() throws Exception {
        if (hook == null) {
            if (!(VerifiedInstall.bind(spec(), context)
                    instanceof VerifiedInstall.Attempt.Installed<SkippedFrameUploadElisionTarget> bound)) {
                throw new IllegalStateException("upload elision refused: host-not-supported-or-hook-disabled");
            }
            hook = bound.hook();
        }
        hook.weave();
    }

    /** Guarded upload call sites across both rewritten wrappers. */
    int sites() {
        int total = 0;
        for (final WeaveTarget target : hook.targets()) {
            total += target.transformer(SkippedFrameUploadElisionTransformer.class)
                    .guarded();
        }
        return total;
    }

    @Override
    public synchronized void close() {
        if (hook == null) return;
        final Map<String, Long> stats = bridge.snapshot();
        try {
            hook.close();
        } catch (RuntimeException | Error failure) {
            log(stats, false);
            throw failure;
        }
        log(stats, hook.restored());
    }

    private static void log(final Map<String, Long> stats, final boolean restored) {
        RuntimeDiagnostics.info(
                "bootstrap",
                "TURBOISM_UPLOAD_ELISION closed elided=" + stats.get("elided")
                        + " passed=" + stats.get("passed") + " calls=" + stats.get("calls")
                        + " clears=" + stats.get("clears") + reportTail(stats)
                        + " restored=" + restored);
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
        return hook != null && hook.restored();
    }
}
