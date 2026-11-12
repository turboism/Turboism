package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.lineupload.LineRendererUploadSplitBridge;
import dev.turboism.adapter.cubism.optimization.lineupload.LineRendererUploadSplitTarget;
import dev.turboism.adapter.cubism.optimization.lineupload.LineRendererUploadSplitTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Exact-host admission, rewrite and verified restoration for the line-renderer upload split.
 *
 * <p>The protocol itself lives in {@link VerifiedInstall}; this class keeps the hook's reviewed
 * target and its dependency-member proof.</p>
 */
final class VerifiedLineRendererUploadSplitInstaller implements AutoCloseable {

    static final String HOOK_ID = "cubism.render.line-renderer-upload-split";

    /** The admission row this hook froze: policy id, kill-switch semantics and JVM predicate. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.ANY);

    /** The one split method this hook rewrites; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_MATCHES = 1;

    /** The close proof re-captures the one rewritten class; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_OWNERS = 1;

    private final InstallContext context;
    private LineRendererUploadSplitBridge bridge;
    private VerifiedInstall<LineRendererUploadSplitTarget> hook;

    VerifiedLineRendererUploadSplitInstaller(final InstallContext context) {
        this.context = context;
    }

    static boolean admitted(final Path artifact, final RuntimeStartupConfig config, final boolean requested)
            throws java.io.IOException {
        // The digest read stays after the shared gate so a refused hook never opens the artifact.
        return VerifiedInstall.admits(
                        ADMISSION, config, requested, Runtime.version().feature())
                && LineRendererUploadSplitTarget.of(HostArtifactDigest.from(artifact))
                        .isPresent();
    }

    private static Optional<LineRendererUploadSplitTarget> resolve(final Path artifact) throws Exception {
        return LineRendererUploadSplitTarget.of(HostArtifactDigest.from(artifact));
    }

    private WeaveSpec<LineRendererUploadSplitTarget> spec() {
        return new WeaveSpec<LineRendererUploadSplitTarget>(
                ADMISSION,
                "line-renderer upload split",
                VerifiedLineRendererUploadSplitInstaller::resolve,
                new WeavePlan<>(VerifiedLineRendererUploadSplitInstaller::targets, WeavePlan.PostCheck.NONE),
                (target, launch) -> {
                    bridge = new LineRendererUploadSplitBridge(launch.hostLoader());
                    return BridgeLayer.of(bridge::install, bridge);
                },
                WeaveEffects.none());
    }

    private static List<WeaveTarget> targets(final LineRendererUploadSplitTarget contract, final WeaveSource source)
            throws Exception {
        final Class<?> target = source.load(LineRendererUploadSplitTarget.OWNER);
        for (final LineRendererUploadSplitTarget.Dep dep : LineRendererUploadSplitTarget.DEPENDENCIES) {
            verifyMethodPresent(source.load(dep.owner()), dep.name(), dep.descriptor());
        }
        final LineRendererUploadSplitTransformer transformer = new LineRendererUploadSplitTransformer(
                source.loader(), source.artifact(), source.referenceBytes(target));
        return List.of(
                WeaveTarget.single(target, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure));
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
     * @throws IllegalStateException when the artifact is not a reviewed line-renderer host
     * @throws Exception when registration, retransformation or the match proof fails
     */
    synchronized void install() throws Exception {
        if (hook != null) return;
        if (!(VerifiedInstall.install(spec(), context)
                instanceof VerifiedInstall.Attempt.Installed<LineRendererUploadSplitTarget> installed)) {
            throw new IllegalStateException("line-renderer upload split refused: host-not-supported-or-hook-disabled");
        }
        hook = installed.hook();
    }

    /** Live work counts for the close marker; empty until this attempt built its bridge. */
    synchronized Map<String, Long> snapshot() {
        return bridge == null ? Map.of() : bridge.snapshot();
    }

    @Override
    public synchronized void close() {
        if (hook != null) hook.close();
    }

    boolean restored() {
        return hook != null && hook.restored();
    }
}
