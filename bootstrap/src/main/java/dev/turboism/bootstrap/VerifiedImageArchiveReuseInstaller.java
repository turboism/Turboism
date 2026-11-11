package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedHostContract;
import dev.turboism.adapter.cubism.optimization.image.ImageArchiveReuseBridge;
import dev.turboism.adapter.cubism.optimization.image.ImageArchiveReuseTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Owns one opt-in, exact-5.3.02 PNG archive optimization and its bytecode restoration.
 *
 * <p>The protocol itself lives in {@link VerifiedInstall}; this class keeps the hook's reviewed
 * contract table. Its transformer reads the official bytes itself, so the target factory only
 * attests the two classes the weave and its bridge name.</p>
 */
final class VerifiedImageArchiveReuseInstaller implements VerifiedHookInstaller {
    static final String HOOK_ID = "cubism.image-archive-reuse";

    /** The admission row this hook froze: policy id, kill-switch semantics and JVM predicate. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.ANY);

    /** The one resource class this hook rewrites; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_MATCHES = 1;

    /** The close proof re-captures the one rewritten class; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_OWNERS = 1;

    private static final String RESOURCE_OWNER = "com/live2d/graphics/CImageResource";
    private static final String IMAGE_OWNER = "com/live2d/graphics/CWritableImage";

    private static final List<ReviewedHostContract.Candidate<String>> CANDIDATES =
            ReviewedHostContract.candidates(ImageArchiveReuseTransformer.reviewedClassSha256(), version -> HOOK_ID);

    static final WeaveSpec<ReviewedHostContract.Bound<String>> SPEC = new WeaveSpec<ReviewedHostContract.Bound<String>>(
            ADMISSION,
            "image archive reuse",
            VerifiedImageArchiveReuseInstaller::resolve,
            new WeavePlan<>(VerifiedImageArchiveReuseInstaller::targets, WeavePlan.PostCheck.NONE),
            (contract, context) -> {
                final ImageArchiveReuseBridge bridge =
                        new ImageArchiveReuseBridge(load(context, RESOURCE_OWNER), load(context, IMAGE_OWNER));
                return BridgeLayer.of(bridge::install, bridge);
            },
            WeaveEffects.none());

    private final InstallContext context;
    private VerifiedInstall<ReviewedHostContract.Bound<String>> hook;

    VerifiedImageArchiveReuseInstaller(final InstallContext context) {
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

    private static Class<?> load(final InstallContext context, final String internalName) throws Exception {
        return Class.forName(internalName.replace('/', '.'), false, context.hostLoader());
    }

    private static List<WeaveTarget> targets(
            final ReviewedHostContract.Bound<String> contract, final WeaveSource source) throws Exception {
        final Class<?> target = source.load(RESOURCE_OWNER);
        // The callback writes the image class's own fields, so it must come from the same loader. The
        // hand-rolled protocol pinned the loader only, and the bridge re-checks both names.
        final Class<?> image = Class.forName(IMAGE_OWNER.replace('/', '.'), false, source.loader());
        if (image.getClassLoader() != source.loader()) {
            throw new IllegalArgumentException("image archive host loader or source mismatch");
        }
        final ImageArchiveReuseTransformer transformer =
                new ImageArchiveReuseTransformer(source.loader(), source.artifact());
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
     * @throws IllegalStateException when the artifact is not an admitted image-archive host
     * @throws Exception when registration, retransformation or the match proof fails
     */
    @Override
    public synchronized void install() throws Exception {
        if (hook != null) return;
        if (!(VerifiedInstall.install(SPEC, context)
                instanceof VerifiedInstall.Attempt.Installed<ReviewedHostContract.Bound<String>> installed)) {
            throw new IllegalStateException("image archive reuse refused: host-not-supported-or-hook-disabled");
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
