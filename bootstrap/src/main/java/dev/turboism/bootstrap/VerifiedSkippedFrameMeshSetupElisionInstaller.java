package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.meshsetup.SkippedFrameMeshSetupElisionBridge;
import dev.turboism.adapter.cubism.optimization.meshsetup.SkippedFrameMeshSetupElisionTarget;
import dev.turboism.adapter.cubism.optimization.meshsetup.SkippedFrameMeshSetupElisionTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * Exact admission, dependency verification, weaving and restoration for the
 * skipped-frame {@code GMesh2.setup} elision.
 *
 * <p>Only the reviewed {@link SkippedFrameMeshSetupElisionTarget#OWNER} setup
 * method is rewritten. The bridge reads the persistent buffer base's protected
 * dirty flag and the frame's {@code GL3} context; every such dependency body is
 * verified against the official artifact before any rewrite. On close the class
 * must hash back to its pre-rewrite SHA-256.</p>
 */
final class VerifiedSkippedFrameMeshSetupElisionInstaller implements AutoCloseable {

    /** Operator kill-switch id checked through the startup hook policy. */
    static final String HOOK_ID = "cubism.render.mesh-setup-elision";

    /** The admission row this hook froze: policy id, kill-switch semantics and JVM predicate. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);

    private final Instrumentation instrumentation;
    private final SkippedFrameMeshSetupElisionTarget target;
    private final Class<?> entry;
    private final SkippedFrameMeshSetupElisionTransformer transformer;
    private final SkippedFrameMeshSetupElisionBridge bridge;
    private boolean installed, restored;

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

    VerifiedSkippedFrameMeshSetupElisionInstaller(
            final Instrumentation instrumentation, final Path artifact, final ClassLoader loader) throws Exception {
        target = SkippedFrameMeshSetupElisionTarget.of(HostArtifactDigest.from(artifact))
                .orElseThrow(() -> new IllegalArgumentException("mesh setup elision unsupported host artifact"));
        if (Runtime.version().feature() < 17) {
            throw new IllegalArgumentException("mesh setup elision requires JVM17+");
        }
        this.instrumentation = instrumentation;
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("retransform unavailable");
        }
        entry = Class.forName(SkippedFrameMeshSetupElisionTarget.OWNER.replace('/', '.'), false, loader);
        VerifiedHookInstallerSupport.attestClassSource(
                entry, loader, artifact, "mesh setup elision dependency loader/source mismatch: " + entry.getName());
        final Map<Class<?>, byte[]> observed = new HashMap<>();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            final byte[] reference = VerifiedHookInstallerSupport.readReferenceBytes(jar, entry);
            if (!SkippedFrameMeshSetupElisionTarget.hasReviewedSetupShape(ReviewedMethodShape.read(
                    reference,
                    SkippedFrameMeshSetupElisionTarget.OWNER,
                    SkippedFrameMeshSetupElisionTarget.METHOD,
                    SkippedFrameMeshSetupElisionTarget.DESCRIPTOR))) {
                throw new IllegalStateException("GMesh2.setup shape is not the reviewed version-compatible body");
            }
            transformer = new SkippedFrameMeshSetupElisionTransformer(loader, artifact, reference);
            verifyMethod(
                    jar,
                    loader,
                    artifact,
                    observed,
                    SkippedFrameMeshSetupElisionTarget.FRAME_OWNER,
                    SkippedFrameMeshSetupElisionTarget.FRAME_METHOD,
                    SkippedFrameMeshSetupElisionTarget.FRAME_DESCRIPTOR);
            verifyMethod(
                    jar,
                    loader,
                    artifact,
                    observed,
                    SkippedFrameMeshSetupElisionTarget.BASE_OWNER,
                    SkippedFrameMeshSetupElisionTarget.DIRTY_METHOD,
                    SkippedFrameMeshSetupElisionTarget.DIRTY_DESCRIPTOR);
            verifyMethod(
                    jar,
                    loader,
                    artifact,
                    observed,
                    SkippedFrameMeshSetupElisionTarget.BASE_OWNER,
                    SkippedFrameMeshSetupElisionTarget.NAME_METHOD,
                    SkippedFrameMeshSetupElisionTarget.NAME_DESCRIPTOR);
            for (final String getter : SkippedFrameMeshSetupElisionTarget.BUFFER_GETTERS) {
                final Class<?> returnType = entry.getMethod(getter).getReturnType();
                final String descriptor =
                        java.lang.invoke.MethodType.methodType(returnType).descriptorString();
                verifyMethod(
                        jar, loader, artifact, observed, SkippedFrameMeshSetupElisionTarget.OWNER, getter, descriptor);
            }
        }
        bridge = new SkippedFrameMeshSetupElisionBridge(loader);
    }

    private void verifyMethod(
            final JarFile jar,
            final ClassLoader loader,
            final Path artifact,
            final Map<Class<?>, byte[]> observed,
            final String owner,
            final String method,
            final String descriptor)
            throws Exception {
        final Class<?> type = Class.forName(owner.replace('/', '.'), false, loader);
        VerifiedHookInstallerSupport.attestClassSource(
                type, loader, artifact, "mesh setup elision dependency loader/source mismatch: " + type.getName());
        byte[] actual = observed.get(type);
        if (actual == null) {
            actual = VerifiedHookInstallerSupport.captureLoadedBytes(
                    instrumentation,
                    type,
                    "mesh setup elision class unmodifiable: " + type.getName(),
                    "mesh setup elision dependency inspection absent: " + type.getName());
            observed.put(type, actual);
        }
        final List<String> expected = ReviewedMethodShape.read(
                VerifiedHookInstallerSupport.readReferenceBytes(jar, type), owner, method, descriptor);
        if (expected == null || !expected.equals(ReviewedMethodShape.read(actual, owner, method, descriptor))) {
            throw new IllegalStateException("mesh setup elision dependency body mismatch: " + owner + "." + method);
        }
    }

    synchronized void install() throws Exception {
        if (installed) return;
        if (!instrumentation.isModifiableClass(entry)) {
            throw new IllegalStateException("mesh setup elision entry unmodifiable: " + entry.getName());
        }
        bridge.install();
        try {
            instrumentation.addTransformer(transformer, true);
            instrumentation.retransformClasses(entry);
            if (transformer.matches() != 1 || transformer.guarded() != 1 || transformer.failure() != null) {
                throw new IllegalStateException("mesh setup elision entry not admitted: " + transformer.failure());
            }
            installed = true;
        } catch (Exception | Error failure) {
            VerifiedHookInstallerSupport.closeOnInstallFailure(failure, this);
            throw failure;
        }
    }

    /** Rewritten setup entry guards so far (one per admitted class). */
    int sites() {
        return transformer.guarded();
    }

    /** Live work counts for the close marker. */
    Map<String, Long> snapshot() {
        return bridge.snapshot();
    }

    boolean restored() {
        return restored;
    }

    @Override
    public synchronized void close() {
        final Map<String, Long> stats = bridge.snapshot();
        bridge.close();
        if (restored) return;
        Throwable failure = null;
        try {
            instrumentation.removeTransformer(transformer);
        } catch (Exception | Error problem) {
            // @containment-exempt: problem is merged and rethrown below with fatal error prioritized
            failure = dev.turboism.core.runtime.work.FatalErrors.mergeForCleanup(failure, problem);
        }
        final String before = transformer.beforeSha256();
        if (before != null) {
            try {
                final String hash =
                        VerifiedHookInstallerSupport.sha256Hex(VerifiedHookInstallerSupport.captureLoadedBytes(
                                instrumentation,
                                entry,
                                "mesh setup elision class unmodifiable: " + entry.getName(),
                                "mesh setup elision dependency inspection absent: " + entry.getName()));
                if (!hash.equals(before)) {
                    throw new IllegalStateException("mesh setup elision restoration not proven: " + entry.getName());
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
        if (failure != null) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
            throw new IllegalStateException("mesh setup elision restoration failed", failure);
        }
    }
}
