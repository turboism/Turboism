package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedHostContract;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.modelupdate.incremental.IncrementalUpdateBridge;
import dev.turboism.adapter.cubism.optimization.modelupdate.incremental.IncrementalUpdateTarget;
import dev.turboism.adapter.cubism.optimization.modelupdate.incremental.IncrementalUpdateTransformer;
import dev.turboism.config.NativeOptimizationDiagnostics;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.config.optimization.OptimizationCatalog;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * Exact incremental-update admission, dependency-body verification and restoration.
 *
 * <p>Five host classes are rewritten: the updater singleton (three methods) and the three
 * interpolated-form classes and the common source rebuild entry (one method each).
 * Every dependency the bridge resolves is verified against the official artifact bytecode via {@link ReviewedMethodShape} before
 * installation; any absent, renamed or changed member fails closed. On close every touched
 * class is re-captured and must hash back to its pre-rewrite SHA-256.</p>
 */
final class VerifiedIncrementalUpdateInstaller implements AutoCloseable {

    static final String HOOK_ID = "cubism.model-update.incremental";

    /** The admission row this hook froze: policy id, kill-switch semantics and JVM predicate. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);

    private final Instrumentation instrumentation;
    private final IncrementalUpdateTarget target;
    private final Class<?>[] entries;
    private final IncrementalUpdateTransformer transformer;
    private final IncrementalUpdateBridge bridge;
    private boolean installed, restored;
    /** True while the glue-affecter sub-mode is reported as installed, so closing reports itself. */
    private boolean affectersReported;

    private static final List<ReviewedHostContract.Candidate<IncrementalUpdateTarget>> CANDIDATES =
            ReviewedHostContract.candidates(
                    IncrementalUpdateTarget.reviewedClassSha256(),
                    version ->
                            IncrementalUpdateTarget.forReviewedVersion(version).orElse(null));

    static boolean admitted(
            final Path artifact, final RuntimeStartupConfig config, final boolean requested, final int jvm) {
        return VerifiedInstall.admits(
                ADMISSION, config, requested, jvm, () -> ReviewedHostContract.resolved(artifact, CANDIDATES));
    }

    VerifiedIncrementalUpdateInstaller(
            final Instrumentation instrumentation, final Path artifact, final ClassLoader loader) throws Exception {
        final var contract = ReviewedHostContract.requireBound(
                ReviewedHostContract.resolve(artifact, CANDIDATES), "incremental update");
        target = contract.contract();
        if (Runtime.version().feature() < 17) {
            throw new IllegalArgumentException("incremental update requires JVM17+");
        }
        this.instrumentation = instrumentation;
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("retransform unavailable");
        }
        entries = new Class<?>[] {
            Class.forName(target.updater().replace('/', '.'), false, loader),
            Class.forName(IncrementalUpdateTarget.ROTATION_FORM, false, loader),
            Class.forName(IncrementalUpdateTarget.WARP_FORM, false, loader),
            Class.forName(IncrementalUpdateTarget.MESH_FORM, false, loader),
            Class.forName(IncrementalUpdateTarget.REINIT_SOURCE, false, loader),
        };
        final Map<Class<?>, byte[]> observed = new HashMap<>();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            final List<byte[]> references = new ArrayList<>(5);
            for (final Class<?> entry : entries) {
                VerifiedHookInstallerSupport.attestClassSource(
                        entry, loader, artifact, "incremental-update dependency loader/source mismatch");
                references.add(VerifiedHookInstallerSupport.readReferenceBytes(jar, entry));
            }
            transformer = new IncrementalUpdateTransformer(loader, artifact, references, target);
            for (final IncrementalUpdateTarget.Dep dep : target.dependencies()) {
                final Class<?> owner = Class.forName(dep.owner(), false, loader);
                VerifiedHookInstallerSupport.attestClassSource(
                        owner, loader, artifact, "incremental-update dependency loader/source mismatch");
                verifyMethod(jar, owner, dep.name(), dep.descriptor(), observed);
            }
        }
        bridge = new IncrementalUpdateBridge(target, loader);
        contract.requireUnchanged(artifact);
    }

    private void verifyMethod(
            final JarFile jar,
            final Class<?> type,
            final String name,
            final String descriptor,
            final Map<Class<?>, byte[]> observed)
            throws Exception {
        byte[] actual = observed.get(type);
        if (actual == null) {
            actual = VerifiedHookInstallerSupport.captureLoadedBytes(
                    instrumentation,
                    type,
                    "incremental-update class unmodifiable",
                    "incremental-update inspection absent");
            observed.put(type, actual);
        }
        final String owner = type.getName().replace('.', '/');
        final var expected = ReviewedMethodShape.read(
                VerifiedHookInstallerSupport.readReferenceBytes(jar, type), owner, name, descriptor);
        if (expected == null) {
            verifyAbstract(type, name, descriptor);
            return;
        }
        if (!expected.equals(ReviewedMethodShape.read(actual, owner, name, descriptor))) {
            throw new IllegalStateException("incremental-update dependency body changed: " + owner + "." + name);
        }
    }

    private static void verifyAbstract(final Class<?> type, final String name, final String descriptor)
            throws NoSuchMethodException {
        for (final java.lang.reflect.Method method : type.getMethods()) {
            if (!method.getName().equals(name)) continue;
            final String actual = java.lang.invoke.MethodType.methodType(
                            method.getReturnType(), method.getParameterTypes())
                    .descriptorString();
            if (actual.equals(descriptor) && java.lang.reflect.Modifier.isAbstract(method.getModifiers())) {
                return;
            }
        }
        throw new NoSuchMethodException("abstract incremental-update dependency absent or concrete: " + type.getName()
                + "." + name + descriptor);
    }

    synchronized void install() throws Exception {
        if (installed) return;
        for (final Class<?> entry : entries) {
            if (!instrumentation.isModifiableClass(entry)) {
                throw new IllegalStateException("incremental-update entry unmodifiable");
            }
        }
        bridge.install();
        try {
            instrumentation.addTransformer(transformer, true);
            installed = true;
            instrumentation.retransformClasses(entries);
            if (transformer.matches() != 7
                    || transformer.failure() != null
                    || transformer.touchedClasses().size() != 5) {
                throw new IllegalStateException("incremental-update entry not admitted: " + transformer.failure());
            }
        } catch (Exception | Error failure) {
            VerifiedHookInstallerSupport.closeOnInstallFailure(failure, this);
            throw failure;
        }
        reportAffecters();
    }

    /**
     * Reports the glue-affecter sub-mode from the point where this installer decides it: the
     * incremental weave owns it, so the bridge's host glue class and affecter switch are the
     * only remaining gates once the entry classes are verified.
     */
    private void reportAffecters() {
        final String refusal = bridge.affecterReuseRefusal();
        if (refusal != null) {
            NativeOptimizationDiagnostics.installed(
                    OptimizationCatalog.PROTOCOL_INCREMENTAL_UPDATE_AFFECTERS, "not-installed", refusal);
            return;
        }
        affectersReported = true;
        NativeOptimizationDiagnostics.installed(
                OptimizationCatalog.PROTOCOL_INCREMENTAL_UPDATE_AFFECTERS, "installed", "verified");
    }

    /** The sub-mode cannot survive the weave it rides, so a closed installer proves nothing. */
    private void reportAffectersClosed() {
        if (!affectersReported) return;
        affectersReported = false;
        NativeOptimizationDiagnostics.installed(
                OptimizationCatalog.PROTOCOL_INCREMENTAL_UPDATE_AFFECTERS, "not-installed", "closed");
    }

    @Override
    public synchronized void close() {
        bridge.close();
        reportAffectersClosed();
        if (!installed) return;
        instrumentation.removeTransformer(transformer);
        try {
            for (final String owner : transformer.touchedClasses()) {
                final Class<?> type = Class.forName(owner.replace('/', '.'), false, entries[0].getClassLoader());
                final byte[] original = VerifiedHookInstallerSupport.captureLoadedBytes(
                        instrumentation,
                        type,
                        "incremental-update class unmodifiable",
                        "incremental-update inspection absent");
                final String hash = VerifiedHookInstallerSupport.sha256Hex(original);
                if (!hash.equals(transformer.beforeSha256(owner))) {
                    throw new IllegalStateException("native incremental-update restoration not proven: " + owner);
                }
            }
            restored = true;
            installed = false;
        } catch (Exception failure) {
            throw new IllegalStateException("incremental-update restoration failed", failure);
        }
    }

    boolean restored() {
        return restored;
    }
}
