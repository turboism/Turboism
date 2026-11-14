package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.extendedinterpolation.ExtendedInterpolationSetupBridge;
import dev.turboism.adapter.cubism.optimization.extendedinterpolation.ExtendedInterpolationSetupTarget;
import dev.turboism.adapter.cubism.optimization.extendedinterpolation.ExtendedInterpolationSetupTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Exact admission, weaving and restoration for the incremental
 * {@code CModelSource.setup()} optimization.
 *
 * <p>Admission is the reviewed artifact digest plus the artifact's own anchor proof: exactly two
 * forced extended-interpolation rebuilds in {@code setup()}, each preceded by the four reviewed
 * default arguments. An unreviewed artifact, a repackaged class or any changed anchor refuses the
 * hook and leaves the official setup path untouched.</p>
 *
 * <p>The protocol itself lives in {@link VerifiedInstall}: the setup owner is retransformed once and
 * re-captured on close, and it must hash back to the bytes the transformer observed.</p>
 */
final class VerifiedExtendedInterpolationSetupInstaller implements AutoCloseable {

    static final String HOOK_ID = "cubism.editor-model.extended-interpolation-incremental-setup";

    /** The admission row this hook froze: the contributor's preference is the only switch. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.SKIP, JvmCheck.ANY);

    /** The two forced rebuild call sites of {@code setup()}; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_MATCHES = ExtendedInterpolationSetupTarget.EXPECTED_CALL_SITES;

    /** The close proof re-captures the one rewritten class; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_OWNERS = 1;

    private final InstallContext context;
    private ExtendedInterpolationSetupBridge bridge;
    private VerifiedInstall<String> hook;

    VerifiedExtendedInterpolationSetupInstaller(
            final Instrumentation instrumentation, final Path artifact, final ClassLoader loader) {
        context = InstallContext.of(instrumentation, artifact, loader);
    }

    private WeaveSpec<String> spec() {
        return new WeaveSpec<String>(
                ADMISSION,
                "incremental setup",
                VerifiedExtendedInterpolationSetupInstaller::resolve,
                new WeavePlan<>(VerifiedExtendedInterpolationSetupInstaller::targets, WeavePlan.PostCheck.NONE),
                (version, launch) -> {
                    bridge = new ExtendedInterpolationSetupBridge(launch.hostLoader());
                    return BridgeLayer.of(bridge::install, bridge);
                },
                WeaveEffects.none());
    }

    private static Optional<String> resolve(final Path artifact) throws Exception {
        return ReviewedHostArtifacts.cubismVersionOf(HostArtifactDigest.cached(artifact));
    }

    private static List<WeaveTarget> targets(final String cubismVersion, final WeaveSource source) throws Exception {
        final Class<?> target = source.load(ExtendedInterpolationSetupTarget.MODEL_SOURCE);
        final ExtendedInterpolationSetupTransformer transformer = new ExtendedInterpolationSetupTransformer(
                source.loader(), source.artifact(), source.referenceBytes(target));
        return List.of(
                WeaveTarget.single(target, transformer, EXPECTED_MATCHES, transformer::sites, transformer::failure));
    }

    int sites() {
        return transformer().sites();
    }

    String cubismVersion() {
        return hook.contract();
    }

    String failure() {
        return transformer().failure();
    }

    Map<String, Long> snapshot() {
        return bridge.snapshot();
    }

    private ExtendedInterpolationSetupTransformer transformer() {
        return hook.targets().get(0).transformer(ExtendedInterpolationSetupTransformer.class);
    }

    /**
     * Installs the weave through {@link VerifiedInstall}.
     *
     * @throws IllegalStateException when the artifact is not a reviewed incremental-setup host
     * @throws Exception when registration, retransformation or the anchor proof fails
     */
    synchronized void install() throws Exception {
        if (hook != null) return;
        if (!(VerifiedInstall.install(spec(), context)
                instanceof VerifiedInstall.Attempt.Installed<String> installed)) {
            throw new IllegalStateException("incremental setup refused: host-not-supported-or-hook-disabled");
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
