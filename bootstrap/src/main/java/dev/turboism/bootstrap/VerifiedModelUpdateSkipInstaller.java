package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedHostContract;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipBridge;
import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipTarget;
import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Exact model-update entry admission, dependency-body verification and restoration.
 *
 * <p>Every getter the predicate or probe reads is verified against the official artifact
 * bytecode via {@link ReviewedMethodShape} before the entry method is rewritten; any
 * absent, renamed or changed dependency fails the installation closed. On close the
 * original entry bytes are re-captured and must hash back to the pre-rewrite SHA-256.</p>
 *
 * <p>The protocol itself lives in {@link VerifiedInstall}; this class keeps the hook's reviewed
 * contract table and its dependency-body proof.</p>
 */
final class VerifiedModelUpdateSkipInstaller implements VerifiedHookInstaller {

    static final String HOOK_ID = "cubism.model-update.unchanged-frame-skip";

    /** The admission row this hook froze: policy id, kill-switch semantics and JVM predicate. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);

    /** The one entry method this hook rewrites; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_MATCHES = 1;

    /** The close proof re-captures the one rewritten class; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_OWNERS = 1;

    private static final List<ReviewedHostContract.Candidate<ModelUpdateSkipTarget>> CANDIDATES =
            ReviewedHostContract.candidates(
                    ModelUpdateSkipTarget.reviewedClassSha256(),
                    version -> ModelUpdateSkipTarget.forReviewedVersion(version).orElse(null));

    static final WeaveSpec<ReviewedHostContract.Bound<ModelUpdateSkipTarget>> SPEC =
            new WeaveSpec<ReviewedHostContract.Bound<ModelUpdateSkipTarget>>(
                    ADMISSION,
                    "model-update skip",
                    VerifiedModelUpdateSkipInstaller::resolve,
                    new WeavePlan<>(VerifiedModelUpdateSkipInstaller::targets, WeavePlan.PostCheck.NONE),
                    (contract, context) -> {
                        final ModelUpdateSkipBridge bridge =
                                new ModelUpdateSkipBridge(contract.contract(), context.hostLoader());
                        return BridgeLayer.of(bridge::install, bridge);
                    },
                    WeaveEffects.none());

    private final InstallContext context;
    private VerifiedInstall<ReviewedHostContract.Bound<ModelUpdateSkipTarget>> hook;

    VerifiedModelUpdateSkipInstaller(final InstallContext context) {
        this.context = context;
    }

    static boolean admitted(
            final Path artifact, final RuntimeStartupConfig config, final boolean requested, final int jvm) {
        return VerifiedInstall.admits(
                ADMISSION, config, requested, jvm, () -> ReviewedHostContract.resolved(artifact, CANDIDATES));
    }

    private static Optional<ReviewedHostContract.Bound<ModelUpdateSkipTarget>> resolve(final Path artifact) {
        return ReviewedHostContract.resolve(artifact, CANDIDATES)
                        instanceof ReviewedHostContract.Bound<ModelUpdateSkipTarget> bound
                ? Optional.of(bound)
                : Optional.empty();
    }

    private static List<WeaveTarget> targets(
            final ReviewedHostContract.Bound<ModelUpdateSkipTarget> contract, final WeaveSource source)
            throws Exception {
        final ModelUpdateSkipTarget target = contract.contract();
        final Class<?> entry = source.load(target.owner());
        final ModelUpdateSkipTransformer transformer = new ModelUpdateSkipTransformer(
                source.loader(), source.artifact(), source.referenceBytes(entry), target);
        final Map<Class<?>, byte[]> observed = new HashMap<>();
        for (final ModelUpdateSkipTarget.Dep dep : target.dependencies()) {
            final Class<?> owner = source.load(dep.owner());
            verifyMethod(source, owner, dep.name(), dep.descriptor(), observed);
        }
        contract.requireUnchanged(source.artifact());
        return List.of(WeaveTarget.single(
                entry,
                transformer,
                EXPECTED_MATCHES,
                transformer::matches,
                transformer::failure,
                owner -> transformer.beforeSha256()));
    }

    private static void verifyMethod(
            final WeaveSource source,
            final Class<?> type,
            final String name,
            final String descriptor,
            final Map<Class<?>, byte[]> observed)
            throws Exception {
        byte[] actual = observed.get(type);
        if (actual == null) {
            actual = VerifiedHookInstallerSupport.captureLoadedBytes(
                    source.instrumentation(),
                    type,
                    "model-update dependency unmodifiable",
                    "model-update dependency inspection absent");
            observed.put(type, actual);
        }
        final String owner = WeaveTarget.internalName(type);
        final var expected = ReviewedMethodShape.read(source.referenceBytes(type), owner, name, descriptor);
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

    @Override
    public boolean admits(final InstallContext context) {
        return admitted(context.artifact(), context.policy(), true, context.jvmFeature());
    }

    /**
     * Installs the weave through {@link VerifiedInstall}.
     *
     * @throws IllegalStateException when the artifact is not an admitted model-update host
     * @throws Exception when registration, retransformation or the match proof fails
     */
    @Override
    public synchronized void install() throws Exception {
        if (hook != null) return;
        if (!(VerifiedInstall.install(SPEC, context)
                instanceof
                VerifiedInstall.Attempt.Installed<ReviewedHostContract.Bound<ModelUpdateSkipTarget>> installed)) {
            throw new IllegalStateException("model-update skip refused: host-not-supported-or-hook-disabled");
        }
        hook = installed.hook();
    }

    @Override
    public synchronized void close() {
        if (hook != null) hook.close();
    }

    boolean restored() {
        return hook != null && hook.restored();
    }
}
