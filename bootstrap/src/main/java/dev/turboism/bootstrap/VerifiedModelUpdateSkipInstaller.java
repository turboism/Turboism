package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedHostContract;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipBridge;
import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipTarget;
import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * Exact model-update entry admission, dependency-body verification and restoration.
 *
 * <p>Every getter the predicate or probe reads is verified against the official artifact
 * bytecode via {@link ReviewedMethodShape} before the entry method is rewritten; any
 * absent, renamed or changed dependency fails the installation closed. On close the
 * original entry bytes are re-captured and must hash back to the pre-rewrite SHA-256.</p>
 */
final class VerifiedModelUpdateSkipInstaller implements AutoCloseable {

    static final String HOOK_ID = "cubism.model-update.unchanged-frame-skip";

    /** The admission row this hook froze: policy id, kill-switch semantics and JVM predicate. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);

    private final Instrumentation instrumentation;
    private final ModelUpdateSkipTarget target;
    private final Class<?> entry;
    private final ModelUpdateSkipTransformer transformer;
    private final ModelUpdateSkipBridge bridge;
    private boolean installed, restored;

    private static final List<ReviewedHostContract.Candidate<ModelUpdateSkipTarget>> CANDIDATES =
            ReviewedHostContract.candidates(
                    ModelUpdateSkipTarget.reviewedClassSha256(),
                    version -> ModelUpdateSkipTarget.forReviewedVersion(version).orElse(null));

    static boolean admitted(
            final Path artifact, final RuntimeStartupConfig config, final boolean requested, final int jvm) {
        return VerifiedInstall.admits(
                ADMISSION, config, requested, jvm, () -> ReviewedHostContract.resolved(artifact, CANDIDATES));
    }

    VerifiedModelUpdateSkipInstaller(
            final Instrumentation instrumentation, final Path artifact, final ClassLoader loader) throws Exception {
        final var contract = ReviewedHostContract.requireBound(
                ReviewedHostContract.resolve(artifact, CANDIDATES), "model-update skip");
        target = contract.contract();
        if (Runtime.version().feature() < 17) {
            throw new IllegalArgumentException("model-update skip requires JVM17+");
        }
        this.instrumentation = instrumentation;
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("retransform unavailable");
        }
        entry = Class.forName(target.owner().replace('/', '.'), false, loader);
        final Map<Class<?>, byte[]> observed = new HashMap<>();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            VerifiedHookInstallerSupport.attestClassSource(
                    entry, loader, artifact, "model-update dependency loader/source mismatch");
            transformer = new ModelUpdateSkipTransformer(
                    loader, artifact, VerifiedHookInstallerSupport.readReferenceBytes(jar, entry), target);
            for (final ModelUpdateSkipTarget.Dep dep : target.dependencies()) {
                final Class<?> owner = Class.forName(dep.owner(), false, loader);
                VerifiedHookInstallerSupport.attestClassSource(
                        owner, loader, artifact, "model-update dependency loader/source mismatch");
                verifyMethod(jar, owner, dep.name(), dep.descriptor(), observed);
            }
        }
        bridge = new ModelUpdateSkipBridge(target, loader);
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
                    "model-update dependency unmodifiable",
                    "model-update dependency inspection absent");
            observed.put(type, actual);
        }
        final String owner = type.getName().replace('.', '/');
        final var expected = ReviewedMethodShape.read(
                VerifiedHookInstallerSupport.readReferenceBytes(jar, type), owner, name, descriptor);
        if (expected == null) {
            // Abstract dependencies have no body to compare; the reviewed table pins the
            // signature and the loaded declaration must still be abstract on the attested class.
            verifyAbstract(type, name, descriptor);
            return;
        }
        if (!expected.equals(ReviewedMethodShape.read(actual, owner, name, descriptor))) {
            throw new IllegalStateException("model-update dependency body changed: " + owner + "." + name);
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
        throw new NoSuchMethodException(
                "abstract model-update dependency absent or concrete: " + type.getName() + "." + name + descriptor);
    }

    synchronized void install() throws Exception {
        if (installed) return;
        if (!instrumentation.isModifiableClass(entry)) {
            throw new IllegalStateException("model-update entry unmodifiable");
        }
        bridge.install();
        try {
            instrumentation.addTransformer(transformer, true);
            installed = true;
            instrumentation.retransformClasses(entry);
            if (transformer.matches() != 1 || transformer.failure() != null) {
                throw new IllegalStateException("model-update entry not admitted: " + transformer.failure());
            }
        } catch (Exception | Error failure) {
            VerifiedHookInstallerSupport.closeOnInstallFailure(failure, this);
            throw failure;
        }
    }

    @Override
    public synchronized void close() {
        bridge.close();
        if (!installed) return;
        instrumentation.removeTransformer(transformer);
        try {
            final byte[] original = VerifiedHookInstallerSupport.captureLoadedBytes(
                    instrumentation,
                    entry,
                    "model-update dependency unmodifiable",
                    "model-update dependency inspection absent");
            final String hash = VerifiedHookInstallerSupport.sha256Hex(original);
            restored = hash.equals(transformer.beforeSha256());
            if (!restored) {
                throw new IllegalStateException("native model-update entry restoration not proven");
            }
            installed = false;
        } catch (Exception failure) {
            throw new IllegalStateException("model-update restoration failed", failure);
        }
    }

    boolean restored() {
        return restored;
    }
}
