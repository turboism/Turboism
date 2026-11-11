package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.meshsetup.SkippedFrameMeshSetupElisionBridge;
import dev.turboism.adapter.cubism.optimization.meshsetup.SkippedFrameMeshSetupElisionTarget;
import dev.turboism.adapter.cubism.optimization.meshsetup.SkippedFrameMeshSetupElisionTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Exact admission, dependency verification, weaving and restoration for the
 * skipped-frame {@code GMesh2.setup} elision.
 *
 * <p>Only the reviewed {@link SkippedFrameMeshSetupElisionTarget#OWNER} setup
 * method is rewritten. The bridge reads the persistent buffer base's protected
 * dirty flag and the frame's {@code GL3} context; every such dependency body is
 * verified against the official artifact before any rewrite. On close the class
 * must hash back to its pre-rewrite SHA-256.</p>
 *
 * <p>The protocol itself lives in {@link VerifiedInstall}; this class keeps the hook's reviewed
 * target, its setup-shape gate and its dependency-body proof.</p>
 */
final class VerifiedSkippedFrameMeshSetupElisionInstaller implements AutoCloseable {

    /** Operator kill-switch id checked through the startup hook policy. */
    static final String HOOK_ID = "cubism.render.mesh-setup-elision";

    /** The admission row this hook froze: policy id, kill-switch semantics and JVM predicate. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);

    /** The one setup method this hook rewrites; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_MATCHES = 1;

    /** The close proof re-captures the one rewritten class; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_OWNERS = 1;

    private final InstallContext context;
    private SkippedFrameMeshSetupElisionBridge bridge;
    private VerifiedInstall<SkippedFrameMeshSetupElisionTarget> hook;

    VerifiedSkippedFrameMeshSetupElisionInstaller(final InstallContext context) {
        this.context = context;
    }

    /**
     * Production admission: the operator requested the hook, the JVM can
     * retransform, the startup policy did not disable the id, and the host
     * artifact is a reviewed version.
     */
    static boolean admitted(
            final HostArtifactDigest digest,
            final RuntimeStartupConfig config,
            final boolean requested,
            final int jvm) {
        return VerifiedInstall.admits(
                ADMISSION,
                config,
                requested,
                jvm,
                () -> SkippedFrameMeshSetupElisionTarget.of(digest).isPresent());
    }

    private WeaveSpec<SkippedFrameMeshSetupElisionTarget> spec() {
        return new WeaveSpec<SkippedFrameMeshSetupElisionTarget>(
                ADMISSION,
                "mesh setup elision",
                VerifiedSkippedFrameMeshSetupElisionInstaller::resolve,
                new WeavePlan<>(
                        VerifiedSkippedFrameMeshSetupElisionInstaller::targets,
                        VerifiedSkippedFrameMeshSetupElisionInstaller::guardProven),
                (target, launch) -> {
                    bridge = new SkippedFrameMeshSetupElisionBridge(launch.hostLoader());
                    return BridgeLayer.of(bridge::install, bridge);
                },
                WeaveEffects.none());
    }

    private static Optional<SkippedFrameMeshSetupElisionTarget> resolve(final Path artifact) throws Exception {
        return SkippedFrameMeshSetupElisionTarget.of(HostArtifactDigest.from(artifact));
    }

    private static List<WeaveTarget> targets(final SkippedFrameMeshSetupElisionTarget target, final WeaveSource source)
            throws Exception {
        final Class<?> entry = source.load(SkippedFrameMeshSetupElisionTarget.OWNER);
        final byte[] reference = source.referenceBytes(entry);
        if (!SkippedFrameMeshSetupElisionTarget.hasReviewedSetupShape(ReviewedMethodShape.read(
                reference,
                SkippedFrameMeshSetupElisionTarget.OWNER,
                SkippedFrameMeshSetupElisionTarget.METHOD,
                SkippedFrameMeshSetupElisionTarget.DESCRIPTOR))) {
            throw new IllegalStateException("GMesh2.setup shape is not the reviewed version-compatible body");
        }
        final SkippedFrameMeshSetupElisionTransformer transformer =
                new SkippedFrameMeshSetupElisionTransformer(source.loader(), source.artifact(), reference);
        final Map<Class<?>, byte[]> observed = new HashMap<>();
        verifyMethod(
                source,
                observed,
                SkippedFrameMeshSetupElisionTarget.FRAME_OWNER,
                SkippedFrameMeshSetupElisionTarget.FRAME_METHOD,
                SkippedFrameMeshSetupElisionTarget.FRAME_DESCRIPTOR);
        verifyMethod(
                source,
                observed,
                SkippedFrameMeshSetupElisionTarget.BASE_OWNER,
                SkippedFrameMeshSetupElisionTarget.DIRTY_METHOD,
                SkippedFrameMeshSetupElisionTarget.DIRTY_DESCRIPTOR);
        verifyMethod(
                source,
                observed,
                SkippedFrameMeshSetupElisionTarget.BASE_OWNER,
                SkippedFrameMeshSetupElisionTarget.NAME_METHOD,
                SkippedFrameMeshSetupElisionTarget.NAME_DESCRIPTOR);
        for (final String getter : SkippedFrameMeshSetupElisionTarget.BUFFER_GETTERS) {
            final Class<?> returnType = entry.getMethod(getter).getReturnType();
            final String descriptor =
                    java.lang.invoke.MethodType.methodType(returnType).descriptorString();
            verifyMethod(source, observed, SkippedFrameMeshSetupElisionTarget.OWNER, getter, descriptor);
        }
        return List.of(
                WeaveTarget.single(entry, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure));
    }

    private static void verifyMethod(
            final WeaveSource source,
            final Map<Class<?>, byte[]> observed,
            final String owner,
            final String method,
            final String descriptor)
            throws Exception {
        final Class<?> type = source.load(owner);
        byte[] actual = observed.get(type);
        if (actual == null) {
            actual = VerifiedHookInstallerSupport.captureLoadedBytes(
                    source.instrumentation(),
                    type,
                    "mesh setup elision class unmodifiable: " + type.getName(),
                    "mesh setup elision dependency inspection absent: " + type.getName());
            observed.put(type, actual);
        }
        final List<String> expected = ReviewedMethodShape.read(source.referenceBytes(type), owner, method, descriptor);
        if (expected == null || !expected.equals(ReviewedMethodShape.read(actual, owner, method, descriptor))) {
            throw new IllegalStateException("mesh setup elision dependency body mismatch: " + owner + "." + method);
        }
    }

    /** The rewrite must guard exactly one setup entry, not merely match the method. */
    private static String guardProven(final List<WeaveTarget> targets) {
        final int guarded = targets.get(0)
                .transformer(SkippedFrameMeshSetupElisionTransformer.class)
                .guarded();
        return guarded == 1 ? null : "guarded=" + guarded;
    }

    /**
     * Installs the weave through {@link VerifiedInstall}.
     *
     * @throws IllegalStateException when the artifact is not a reviewed mesh-setup host
     * @throws Exception when registration, retransformation or the match proof fails
     */
    synchronized void install() throws Exception {
        if (hook != null) return;
        if (!(VerifiedInstall.install(spec(), context)
                instanceof VerifiedInstall.Attempt.Installed<SkippedFrameMeshSetupElisionTarget> installed)) {
            throw new IllegalStateException("mesh setup elision refused: host-not-supported-or-hook-disabled");
        }
        hook = installed.hook();
    }

    /** Rewritten setup entry guards so far (one per admitted class). */
    int sites() {
        return hook.targets()
                .get(0)
                .transformer(SkippedFrameMeshSetupElisionTransformer.class)
                .guarded();
    }

    /** Live work counts for the close marker; empty until this attempt built its bridge. */
    Map<String, Long> snapshot() {
        return bridge == null ? Map.of() : bridge.snapshot();
    }

    boolean restored() {
        return hook != null && hook.restored();
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
                "TURBOISM_MESH_SETUP_ELISION closed elided=" + stats.get("elided")
                        + " passed=" + stats.get("passed") + " calls=" + stats.get("calls")
                        + " skippedCalls=" + stats.get("skippedCalls")
                        + " passNotSkipped=" + stats.get("passNotSkipped")
                        + " passContext=" + stats.get("passContext")
                        + " passNoBaseline=" + stats.get("passNoBaseline")
                        + " passDirty=" + stats.get("passDirty")
                        + " completions=" + stats.get("completions")
                        + " failures=" + stats.get("failures")
                        + " observerFailures=" + stats.get("observerFailures")
                        + " contextChanges=" + stats.get("contextChanges")
                        + " tracked=" + stats.get("tracked")
                        + " restored=" + restored);
    }
}
