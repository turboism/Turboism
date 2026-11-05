package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.glerror.GlGetErrorElisionTarget;
import dev.turboism.adapter.cubism.optimization.glerror.GlGetErrorElisionTransformer;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Exact glGetError-elision admission and restoration for the test-only timing-leg experiment. One host
 * class is rewritten: the shader helper owning the unconditional error-check marker.
 *
 * <p>The protocol itself lives in {@link VerifiedInstall}. Its admission row never consults the startup
 * policy — the sole switch is {@code -Dturboism.validation.glGetErrorElision=true} — and it publishes
 * the composed method shape only after the rewrite is verified, then withdraws it before the
 * restoration capture so bytes that stay elided are no longer claimed as ours.</p>
 */
final class VerifiedGlGetErrorElisionInstaller implements AutoCloseable {

    /** The one switch of this test-only experiment; not a policy id. */
    static final String HOOK_ID = "TURBOISM_GL_ERROR_ELISION";

    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.SKIP, JvmCheck.AT_LEAST_17);

    /** Publishes the composed method shape only after the weave is proven, and withdraws it before the capture. */
    private static final WeaveEffects<GlGetErrorElisionTarget> EFFECTS = new WeaveEffects<GlGetErrorElisionTarget>() {
        @Override
        public void confirmed(final VerifiedInstall<GlGetErrorElisionTarget> hook) {
            GlGetErrorElisionTransformer.markInstalled(
                    hook.context().hostLoader(), hook.contract().owner());
        }

        @Override
        public void withdrawn(final VerifiedInstall<GlGetErrorElisionTarget> hook) {
            GlGetErrorElisionTransformer.clearInstalled(
                    hook.context().hostLoader(), hook.contract().owner());
        }
    };

    static final WeaveSpec<GlGetErrorElisionTarget> SPEC = new WeaveSpec<>(
            ADMISSION,
            "glGetError elision",
            VerifiedGlGetErrorElisionInstaller::resolve,
            new WeavePlan<>(VerifiedGlGetErrorElisionInstaller::targets, VerifiedGlGetErrorElisionInstaller::unelided),
            BridgeLayer.NONE,
            EFFECTS);

    private final InstallContext context;
    private VerifiedInstall<GlGetErrorElisionTarget> hook;

    VerifiedGlGetErrorElisionInstaller(
            final Instrumentation instrumentation, final Path artifact, final ClassLoader loader) {
        context = InstallContext.of(instrumentation, artifact, loader);
    }

    private static Optional<GlGetErrorElisionTarget> resolve(final Path artifact) throws Exception {
        return GlGetErrorElisionTarget.of(HostArtifactDigest.from(artifact));
    }

    /** The experiment declares exactly one target, so the post-check reads that one. */
    private static String unelided(final List<WeaveTarget> targets) {
        return targets.get(0).transformer(GlGetErrorElisionTransformer.class).elided() < 1
                ? "no glGetError call site was elided"
                : null;
    }

    private static List<WeaveTarget> targets(final GlGetErrorElisionTarget target, final WeaveSource source)
            throws Exception {
        final Class<?> entry = source.load(target.owner());
        final var transformer = new GlGetErrorElisionTransformer(
                source.loader(), source.artifact(), source.referenceBytes(entry), target);
        return List.of(WeaveTarget.single(
                entry,
                transformer,
                1,
                transformer::matches,
                transformer::failure,
                owner -> transformer.beforeSha256()));
    }

    /**
     * Installs the weave through {@link VerifiedInstall}.
     *
     * @throws IllegalArgumentException when the artifact is not a reviewed glGetError-elision host
     * @throws Exception when registration, retransformation or the elision proof fails
     */
    synchronized void install() throws Exception {
        if (hook != null) return;
        if (!(VerifiedInstall.install(SPEC, context)
                instanceof VerifiedInstall.Attempt.Installed<GlGetErrorElisionTarget> installed)) {
            throw new IllegalArgumentException("glGetError elision unsupported host artifact");
        }
        hook = installed.hook();
    }

    /** Returns the replaced {@code glGetError} site count for the install marker log. */
    int elided() {
        return hook.targets()
                .get(0)
                .transformer(GlGetErrorElisionTransformer.class)
                .elided();
    }

    /** Returns the reviewed owner/method/descriptor for the install marker log. */
    String targetDescription() {
        final GlGetErrorElisionTarget target = hook.contract();
        return target.owner() + "." + target.method() + target.descriptor();
    }

    @Override
    public synchronized void close() {
        if (hook != null) hook.close();
    }

    boolean restored() {
        return hook != null && hook.restored();
    }
}
