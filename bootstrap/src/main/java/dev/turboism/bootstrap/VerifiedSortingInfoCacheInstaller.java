package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.sorting.MeshContainerLookupTransformer;
import dev.turboism.adapter.cubism.optimization.sorting.SortingInfoCacheTarget;
import dev.turboism.adapter.cubism.optimization.sorting.SortingInfoCacheTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Exact-host admission, rewrite and verified restoration for the skipped-frame
 * sorting-info cache and allocation-free {@code GMeshRenderer.getMeshContainer()} lookup.
 *
 * <p>The protocol itself lives in {@link VerifiedInstall}; this class keeps the hook's two targets and
 * their dependency-shape proof. Both transforms own one class each, so the routine retransforms both
 * in one call and re-captures both on close.</p>
 */
final class VerifiedSortingInfoCacheInstaller implements AutoCloseable {

    static final String HOOK_ID = "cubism.render.sorting-info-cache";

    /** The admission row this hook froze: policy id, kill-switch semantics and JVM predicate. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.ANY);

    static final String ENABLE_PROPERTY = "turboism.optimization.sortingInfoCache";

    /** Each transformer rewrites exactly one site; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_MATCHES = 1;

    /** The close proof re-captures both rewritten classes; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_OWNERS = 2;

    static final WeaveSpec<SortingInfoCacheTarget> SPEC = new WeaveSpec<SortingInfoCacheTarget>(
            ADMISSION,
            "sorting-info cache",
            VerifiedSortingInfoCacheInstaller::resolve,
            new WeavePlan<>(VerifiedSortingInfoCacheInstaller::targets, WeavePlan.PostCheck.NONE),
            BridgeFactory.none(),
            WeaveEffects.none());

    private final InstallContext context;
    private VerifiedInstall<SortingInfoCacheTarget> hook;

    VerifiedSortingInfoCacheInstaller(final InstallContext context) {
        this.context = context;
    }

    static boolean admitted(final Path artifact, final RuntimeStartupConfig config, final boolean requested)
            throws java.io.IOException {
        // The digest read stays after the shared gate so a refused hook never opens the artifact.
        return VerifiedInstall.admits(
                        ADMISSION, config, requested, Runtime.version().feature())
                && SortingInfoCacheTarget.of(HostArtifactDigest.from(artifact)).isPresent();
    }

    private static Optional<SortingInfoCacheTarget> resolve(final Path artifact) throws Exception {
        return SortingInfoCacheTarget.of(HostArtifactDigest.from(artifact));
    }

    private static List<WeaveTarget> targets(final SortingInfoCacheTarget target, final WeaveSource source)
            throws Exception {
        final Class<?> sortingOwner = source.load(SortingInfoCacheTarget.SORTING_OWNER);
        final Class<?> meshRendererOwner = source.load(SortingInfoCacheTarget.MESH_RENDERER_OWNER);
        for (final SortingInfoCacheTarget.Dep dep : target.dependencies()) {
            verifyMethodPresent(source.load(dep.owner()), dep.name(), dep.descriptor());
        }
        final SortingInfoCacheTransformer sortingTransformer = new SortingInfoCacheTransformer(
                source.loader(), source.artifact(), source.referenceBytes(sortingOwner), target);
        final MeshContainerLookupTransformer containerTransformer = new MeshContainerLookupTransformer(
                source.loader(), source.artifact(), source.referenceBytes(meshRendererOwner));
        return List.of(
                WeaveTarget.single(
                        sortingOwner,
                        sortingTransformer,
                        EXPECTED_MATCHES,
                        sortingTransformer::matches,
                        sortingTransformer::failure,
                        owner -> sortingTransformer.beforeSha256()),
                WeaveTarget.single(
                        meshRendererOwner,
                        containerTransformer,
                        EXPECTED_MATCHES,
                        containerTransformer::matches,
                        containerTransformer::failure,
                        owner -> containerTransformer.beforeSha256()));
    }

    private static void verifyMethodPresent(final Class<?> owner, final String name, final String descriptor)
            throws NoSuchMethodException {
        for (final Method method : owner.getMethods()) {
            if (!method.getName().equals(name)) continue;
            final String desc = MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                    .descriptorString();
            if (desc.equals(descriptor)) return;
        }
        throw new NoSuchMethodException(owner.getName() + "." + name + descriptor);
    }

    /**
     * Installs the weave through {@link VerifiedInstall}.
     *
     * @throws IllegalStateException when the artifact is not a reviewed sorting-info host
     * @throws Exception when registration, retransformation or the match proof fails
     */
    synchronized void install() throws Exception {
        if (hook != null) return;
        if (!(VerifiedInstall.install(SPEC, context)
                instanceof VerifiedInstall.Attempt.Installed<SortingInfoCacheTarget> installed)) {
            throw new IllegalStateException("sorting-info cache refused: host-not-supported-or-hook-disabled");
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
