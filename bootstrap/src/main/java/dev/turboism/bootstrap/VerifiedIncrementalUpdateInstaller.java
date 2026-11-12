package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedHostContract;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.modelupdate.incremental.IncrementalUpdateBridge;
import dev.turboism.adapter.cubism.optimization.modelupdate.incremental.IncrementalUpdateTarget;
import dev.turboism.adapter.cubism.optimization.modelupdate.incremental.IncrementalUpdateTransformer;
import dev.turboism.config.NativeOptimizationDiagnostics;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.config.optimization.OptimizationCatalog;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Exact incremental-update admission, dependency-body verification and restoration.
 *
 * <p>Five host classes are rewritten: the updater singleton (three methods) and the three
 * interpolated-form classes and the common source rebuild entry (one method each).
 * Every dependency the bridge resolves is verified against the official artifact bytecode via {@link ReviewedMethodShape} before
 * installation; any absent, renamed or changed member fails closed. On close every touched
 * class is re-captured and must hash back to its pre-rewrite SHA-256.</p>
 *
 * <p>The protocol itself lives in {@link VerifiedInstall}: the one transformer declares one target
 * per rewritten class, so the routine registers it once, retransforms all five classes in one call
 * and re-captures each of them on close. The glue-affecter sub-mode is reported from
 * {@link WeaveEffects}, i.e. only once the weave is proven and never after the handle closed.</p>
 */
final class VerifiedIncrementalUpdateInstaller implements VerifiedHookInstaller {

    static final String HOOK_ID = "cubism.model-update.incremental";

    /** The admission row this hook froze: policy id, kill-switch semantics and JVM predicate. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);

    /** The transformer's matched sites across all five rewritten classes; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_MATCHES = 7;

    /** The close proof re-captures all five rewritten classes; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_OWNERS = 5;

    private static final List<ReviewedHostContract.Candidate<IncrementalUpdateTarget>> CANDIDATES =
            ReviewedHostContract.candidates(
                    IncrementalUpdateTarget.reviewedClassSha256(),
                    version ->
                            IncrementalUpdateTarget.forReviewedVersion(version).orElse(null));

    private final InstallContext context;
    private IncrementalUpdateBridge bridge;
    private boolean affectersReported;
    private VerifiedInstall<ReviewedHostContract.Bound<IncrementalUpdateTarget>> hook;

    VerifiedIncrementalUpdateInstaller(final InstallContext context) {
        this.context = context;
    }

    static boolean admitted(
            final Path artifact, final RuntimeStartupConfig config, final boolean requested, final int jvm) {
        return VerifiedInstall.admits(
                ADMISSION, config, requested, jvm, () -> ReviewedHostContract.resolved(artifact, CANDIDATES));
    }

    private static Optional<ReviewedHostContract.Bound<IncrementalUpdateTarget>> resolve(final Path artifact) {
        return ReviewedHostContract.resolve(artifact, CANDIDATES)
                        instanceof ReviewedHostContract.Bound<IncrementalUpdateTarget> bound
                ? Optional.of(bound)
                : Optional.empty();
    }

    /**
     * @return the hook's spec, whose side effects report the glue-affecter sub-mode from the point where
     *     this installer decides it: the incremental weave owns it, so the bridge's host glue class and
     *     affecter switch are the only remaining gates once the entry classes are verified
     */
    private WeaveSpec<ReviewedHostContract.Bound<IncrementalUpdateTarget>> spec() {
        return new WeaveSpec<ReviewedHostContract.Bound<IncrementalUpdateTarget>>(
                ADMISSION,
                "incremental update",
                VerifiedIncrementalUpdateInstaller::resolve,
                new WeavePlan<>(
                        VerifiedIncrementalUpdateInstaller::targets,
                        VerifiedIncrementalUpdateInstaller::everyClassTouched),
                (contract, launch) -> {
                    bridge = new IncrementalUpdateBridge(contract.contract(), launch.hostLoader());
                    return BridgeLayer.of(bridge::install, bridge);
                },
                new WeaveEffects<ReviewedHostContract.Bound<IncrementalUpdateTarget>>() {
                    @Override
                    public void confirmed(
                            final VerifiedInstall<ReviewedHostContract.Bound<IncrementalUpdateTarget>> installed) {
                        reportAffecters();
                    }

                    @Override
                    public void withdrawn(
                            final VerifiedInstall<ReviewedHostContract.Bound<IncrementalUpdateTarget>> closing) {
                        reportAffectersClosed();
                    }
                });
    }

    private static List<WeaveTarget> targets(
            final ReviewedHostContract.Bound<IncrementalUpdateTarget> contract, final WeaveSource source)
            throws Exception {
        final IncrementalUpdateTarget target = contract.contract();
        final Class<?>[] entries = {
            source.load(target.updater()),
            source.load(IncrementalUpdateTarget.ROTATION_FORM),
            source.load(IncrementalUpdateTarget.WARP_FORM),
            source.load(IncrementalUpdateTarget.MESH_FORM),
            source.load(IncrementalUpdateTarget.REINIT_SOURCE),
        };
        final Map<Class<?>, byte[]> observed = new HashMap<>();
        final List<byte[]> references = new ArrayList<>(entries.length);
        for (final Class<?> entry : entries) {
            references.add(source.referenceBytes(entry));
        }
        final IncrementalUpdateTransformer transformer =
                new IncrementalUpdateTransformer(source.loader(), source.artifact(), references, target);
        for (final IncrementalUpdateTarget.Dep dep : target.dependencies()) {
            final Class<?> owner = source.load(dep.owner());
            verifyMethod(source, owner, dep.name(), dep.descriptor(), observed);
        }
        contract.requireUnchanged(source.artifact());
        final List<String> owners = transformer.classNames();
        final List<WeaveTarget> targets = new ArrayList<>(entries.length);
        for (int i = 0; i < entries.length; i++) {
            targets.add(new WeaveTarget(
                    entries[i], transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure));
        }
        return targets;
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
                    "incremental-update class unmodifiable",
                    "incremental-update inspection absent");
            observed.put(type, actual);
        }
        final String owner = WeaveTarget.internalName(type);
        final var expected = ReviewedMethodShape.read(source.referenceBytes(type), owner, name, descriptor);
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

    /** All five entry classes must have been rewritten, not merely the reviewed methods matched. */
    private static String everyClassTouched(final List<WeaveTarget> targets) {
        final int touched = targets.get(0)
                .transformer(IncrementalUpdateTransformer.class)
                .touchedClasses()
                .size();
        return touched == EXPECTED_OWNERS ? null : "touched=" + touched;
    }

    /** Reports the glue-affecter sub-mode once the weave is proven. */
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
    public boolean admits(final InstallContext context) {
        return admitted(context.artifact(), context.policy(), true, context.jvmFeature());
    }

    /**
     * Installs the weave through {@link VerifiedInstall}.
     *
     * @throws IllegalStateException when the artifact is not an admitted incremental-update host
     * @throws Exception when registration, retransformation or the match proof fails
     */
    @Override
    public synchronized void install() throws Exception {
        if (hook != null) return;
        if (!(VerifiedInstall.install(spec(), context)
                instanceof
                VerifiedInstall.Attempt.Installed<ReviewedHostContract.Bound<IncrementalUpdateTarget>> installed)) {
            throw new IllegalStateException("incremental update refused: host-not-supported-or-hook-disabled");
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
