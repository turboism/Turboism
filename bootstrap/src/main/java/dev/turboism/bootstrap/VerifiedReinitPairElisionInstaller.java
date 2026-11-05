package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedHostContract;
import dev.turboism.adapter.cubism.optimization.pair.ReinitPairElisionTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Exact-host admission, one-shot rewrite and verified restoration for the reinit Pair elision.
 *
 * <p>The protocol itself lives in {@link VerifiedInstall}; this class keeps the hook's reviewed contract
 * table and, for the slice that migrates it, the install surface its contributor already used. Its
 * contract payload is the {@link ReviewedHostContract.Bound bound} resolution itself, because the
 * reference bytes are read after binding and must still come from the bound snapshot.</p>
 */
final class VerifiedReinitPairElisionInstaller implements AutoCloseable {
    static final String HOOK_ID = "cubism.model.reinit-pair-elision";

    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.ANY);

    private static final List<ReviewedHostContract.Candidate<String>> CANDIDATES =
            ReviewedHostContract.candidates(ReinitPairElisionTransformer.reviewedClassSha256(), version -> HOOK_ID);

    static final WeaveSpec<ReviewedHostContract.Bound<String>> SPEC = new WeaveSpec<>(
            ADMISSION,
            "reinit Pair elision",
            VerifiedReinitPairElisionInstaller::resolve,
            new WeavePlan<>(VerifiedReinitPairElisionInstaller::targets, WeavePlan.PostCheck.NONE),
            BridgeLayer.NONE,
            WeaveEffects.none());

    private final InstallContext context;
    private VerifiedInstall<ReviewedHostContract.Bound<String>> hook;

    VerifiedReinitPairElisionInstaller(final InstallContext context) {
        this.context = context;
    }

    static boolean admitted(final Path artifact, final RuntimeStartupConfig config, final boolean requested) {
        return VerifiedInstall.admits(
                ADMISSION,
                config,
                requested,
                Runtime.version().feature(),
                () -> ReviewedHostContract.resolved(artifact, CANDIDATES));
    }

    private static Optional<ReviewedHostContract.Bound<String>> resolve(final Path artifact) {
        return ReviewedHostContract.resolve(artifact, CANDIDATES) instanceof ReviewedHostContract.Bound<String> bound
                ? Optional.of(bound)
                : Optional.empty();
    }

    private static List<WeaveTarget> targets(
            final ReviewedHostContract.Bound<String> contract, final WeaveSource source) throws Exception {
        final Class<?> target = source.load(ReinitPairElisionTransformer.OWNER);
        final var transformer =
                new ReinitPairElisionTransformer(source.loader(), source.artifact(), source.referenceBytes(target));
        contract.requireUnchanged(source.artifact());
        return List.of(WeaveTarget.single(
                target,
                transformer,
                1,
                transformer::matches,
                transformer::failure,
                owner -> transformer.beforeSha256()));
    }

    /**
     * Installs the weave through {@link VerifiedInstall}.
     *
     * @throws IllegalStateException when the artifact is not an admitted reinit Pair host
     * @throws Exception when registration, retransformation or the match proof fails
     */
    synchronized void install() throws Exception {
        if (hook != null) return;
        if (!(VerifiedInstall.install(SPEC, context)
                instanceof VerifiedInstall.Attempt.Installed<ReviewedHostContract.Bound<String>> installed)) {
            throw new IllegalStateException("reinit Pair elision refused: host-not-supported-or-hook-disabled");
        }
        hook = installed.hook();
    }

    @Override
    public synchronized void close() {
        if (hook != null) hook.close();
    }
}
