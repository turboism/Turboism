package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedHostContract;
import dev.turboism.adapter.cubism.optimization.serialization.FloatArrayParseBridge;
import dev.turboism.adapter.cubism.optimization.serialization.FloatArrayParseTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Exact native serializer admission, callback lifetime and verified bytecode restoration.
 *
 * <p>The protocol itself lives in {@link VerifiedInstall}; this class keeps the hook's reviewed
 * contract table and owns the one slot bridge the rewritten parser calls.</p>
 */
final class VerifiedFloatArrayParseCacheInstaller implements VerifiedHookInstaller {
    static final String HOOK_ID = "cubism.float-array-parse-cache";

    /** The admission row this hook froze: policy id, kill-switch semantics and JVM predicate. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.ANY);

    /** The one parser method this hook rewrites; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_MATCHES = 1;

    /** The close proof re-captures the one rewritten class; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_OWNERS = 1;

    private static final List<ReviewedHostContract.Candidate<String>> CANDIDATES =
            ReviewedHostContract.candidates(FloatArrayParseTransformer.reviewedClassSha256(), version -> HOOK_ID);

    static final WeaveSpec<ReviewedHostContract.Bound<String>> SPEC = new WeaveSpec<ReviewedHostContract.Bound<String>>(
            ADMISSION,
            "float array parse cache",
            VerifiedFloatArrayParseCacheInstaller::resolve,
            new WeavePlan<>(VerifiedFloatArrayParseCacheInstaller::targets, WeavePlan.PostCheck.NONE),
            (contract, context) -> {
                final FloatArrayParseBridge bridge = new FloatArrayParseBridge();
                return BridgeLayer.of(bridge::install, bridge);
            },
            WeaveEffects.none());

    private final InstallContext context;
    private VerifiedInstall<ReviewedHostContract.Bound<String>> hook;

    VerifiedFloatArrayParseCacheInstaller(final InstallContext context) {
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
        final Class<?> target = source.load(FloatArrayParseTransformer.TARGET);
        final FloatArrayParseTransformer transformer =
                new FloatArrayParseTransformer(source.loader(), source.artifact(), source.referenceBytes(target));
        contract.requireUnchanged(source.artifact());
        return List.of(WeaveTarget.single(
                target,
                transformer,
                EXPECTED_MATCHES,
                transformer::matches,
                transformer::failure,
                owner -> transformer.beforeSha256()));
    }

    @Override
    public boolean admits(final InstallContext context) {
        return admitted(context.artifact(), context.policy(), true);
    }

    /**
     * Installs the weave through {@link VerifiedInstall}.
     *
     * @throws IllegalStateException when the artifact is not an admitted float-array host
     * @throws Exception when registration, retransformation or the match proof fails
     */
    @Override
    public synchronized void install() throws Exception {
        if (hook != null) return;
        if (!(VerifiedInstall.install(SPEC, context)
                instanceof VerifiedInstall.Attempt.Installed<ReviewedHostContract.Bound<String>> installed)) {
            throw new IllegalStateException("float array parse cache refused: host-not-supported-or-hook-disabled");
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
