package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.deferred.DeferredGlErrorCheckTarget;
import dev.turboism.adapter.cubism.optimization.deferred.DeferredGlErrorCheckTransformer;
import dev.turboism.adapter.cubism.optimization.glerror.GlGetErrorElisionTransformer;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationHookBridge;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationLifecycleTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Exact deferred GL error-check admission and restoration for the production
 * {@code launcher.mesaGlThread} option. Two host classes are rewritten by one transformer: the
 * shader error-check helper (its {@code glGetError} site becomes a deferred
 * checkpoint) and the render-scope owner (every {@code render3d} return
 * consults the armed frame-end report). Reference bytecode comes from the
 * official artifact; when the upstream uniform-location lifecycle transform
 * is installed, the composed shapes it produces over the same reference bytes
 * are admitted explicitly via a private probe — never by trusting observed
 * chain output. On close each class must hash back to its pre-rewrite
 * SHA-256.
 *
 * <p>The protocol itself lives in {@link VerifiedInstall}. One transformer declared against two
 * classes is registered once and retransforms both in one call, so the composed shape it accepts
 * covers the whole rewrite set; the close proof then compares each owner against the digest the
 * transformer observed before it rewrote that owner.</p>
 */
final class VerifiedDeferredGlErrorCheckInstaller implements AutoCloseable {

    static final String HOOK_ID = "cubism.render.mesa-gl-thread";

    /** The admission row this hook froze: policy id, kill-switch semantics and JVM predicate. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);

    /** The transformer's matched sites across both rewritten classes; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_MATCHES = 2;

    /** The close proof re-captures both rewritten classes; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_OWNERS = 2;

    static final WeaveSpec<DeferredGlErrorCheckTarget> SPEC = new WeaveSpec<DeferredGlErrorCheckTarget>(
            ADMISSION,
            "deferred error check",
            VerifiedDeferredGlErrorCheckInstaller::resolve,
            new WeavePlan<>(VerifiedDeferredGlErrorCheckInstaller::targets, WeavePlan.PostCheck.NONE),
            BridgeFactory.none(),
            WeaveEffects.none());

    private final InstallContext context;
    private VerifiedInstall<DeferredGlErrorCheckTarget> hook;

    VerifiedDeferredGlErrorCheckInstaller(final InstallContext context) {
        this.context = context;
    }

    static boolean admitted(
            final HostArtifactDigest digest,
            final RuntimeStartupConfig config,
            final boolean requested,
            final int jvm) {
        return VerifiedInstall.admits(
                ADMISSION,
                config,
                requested,
                jvm,
                () -> DeferredGlErrorCheckTarget.of(digest).isPresent());
    }

    private static Optional<DeferredGlErrorCheckTarget> resolve(final Path artifact) throws Exception {
        return DeferredGlErrorCheckTarget.of(HostArtifactDigest.from(artifact));
    }

    private static List<WeaveTarget> targets(final DeferredGlErrorCheckTarget target, final WeaveSource source)
            throws Exception {
        final Class<?> errorType = source.load(target.errorOwner());
        final Class<?> frameType = source.load(target.frameOwner());
        if (GlGetErrorElisionTransformer.isInstalled(source.loader(), target.errorOwner())) {
            // The test-only elision already consumed the error-check site;
            // deferred checking is the production replacement, not a stacking
            // mode, so the pair stays mutually exclusive.
            throw new IllegalStateException("deferred error check excludes the test-only elision");
        }
        // The frame-end report needs the host logger and GLException shape the
        // bridge binds lazily; refuse admission up front when they are absent.
        if (UniformLocationHookBridge.DeferredAccessors.resolve(source.loader()) == null) {
            throw new IllegalStateException("deferred error check report path absent");
        }
        final byte[] errorReference = source.referenceBytes(errorType);
        final byte[] frameReference = source.referenceBytes(frameType);
        final DeferredGlErrorCheckTransformer transformer = new DeferredGlErrorCheckTransformer(
                source.loader(), source.artifact(), errorReference, frameReference, target);
        final List<String> errorComposed = composedShape(
                source,
                errorReference,
                UniformLocationLifecycleTransformer.Role.ERROR,
                errorType,
                target.errorOwner(),
                DeferredGlErrorCheckTarget.ERROR_METHOD,
                DeferredGlErrorCheckTarget.ERROR_DESCRIPTOR);
        if (errorComposed != null) {
            transformer.acceptComposedShape(target.errorOwner(), errorComposed);
        }
        final List<String> frameComposed = composedShape(
                source,
                frameReference,
                UniformLocationLifecycleTransformer.Role.FRAME,
                frameType,
                target.frameOwner(),
                DeferredGlErrorCheckTarget.FRAME_METHOD,
                DeferredGlErrorCheckTarget.FRAME_DESCRIPTOR);
        if (frameComposed != null) {
            transformer.acceptComposedShape(target.frameOwner(), frameComposed);
        }
        verify(
                errorType,
                errorReference,
                target.errorOwner(),
                DeferredGlErrorCheckTarget.ERROR_METHOD,
                DeferredGlErrorCheckTarget.ERROR_DESCRIPTOR,
                errorComposed,
                source);
        verify(
                frameType,
                frameReference,
                target.frameOwner(),
                DeferredGlErrorCheckTarget.FRAME_METHOD,
                DeferredGlErrorCheckTarget.FRAME_DESCRIPTOR,
                frameComposed,
                source);
        return List.of(
                new WeaveTarget(
                        errorType,
                        transformer,
                        EXPECTED_MATCHES,
                        transformer::matches,
                        transformer::failure,
                        Set.of(target.errorOwner()),
                        transformer::beforeSha256),
                new WeaveTarget(
                        frameType,
                        transformer,
                        EXPECTED_MATCHES,
                        transformer::matches,
                        transformer::failure,
                        Set.of(target.frameOwner()),
                        transformer::beforeSha256));
    }

    /**
     * Runs the upstream lifecycle transform over the attested reference bytes
     * and reads back the exact method shape it produces — the input this
     * transform observes when that hook is installed. {@code null} when the
     * upstream rewrite does not apply; only the official shape is then
     * admitted. The shape is derived, never trusted from chain output.
     */
    private static List<String> composedShape(
            final WeaveSource source,
            final byte[] reference,
            final UniformLocationLifecycleTransformer.Role role,
            final Class<?> type,
            final String owner,
            final String method,
            final String descriptor) {
        try {
            final var probe = new UniformLocationLifecycleTransformer(
                    source.loader(), source.artifact(), reference, role, HostArtifactDigest.from(source.artifact()));
            final byte[] output = probe.transform(
                    type.getModule(),
                    source.loader(),
                    type.getName().replace('.', '/'),
                    null,
                    type.getProtectionDomain(),
                    reference);
            return output == null ? null : ReviewedMethodShape.read(output, owner, method, descriptor);
        } catch (Exception | LinkageError failure) {
            return null;
        }
    }

    private static void verify(
            final Class<?> type,
            final byte[] reference,
            final String owner,
            final String method,
            final String descriptor,
            final List<String> composed,
            final WeaveSource source)
            throws Exception {
        final byte[] actual = VerifiedHookInstallerSupport.captureLoadedBytes(
                source.instrumentation(),
                type,
                "deferred-check class unmodifiable: " + type.getName(),
                "deferred-check capture absent: " + type.getName());
        final List<String> official = ReviewedMethodShape.read(reference, owner, method, descriptor);
        final List<String> observed = ReviewedMethodShape.read(actual, owner, method, descriptor);
        if (official == null || observed == null || (!official.equals(observed) && !observed.equals(composed))) {
            throw new IllegalStateException("deferred-check body mismatch: " + owner);
        }
    }

    /**
     * Installs the weave through {@link VerifiedInstall}.
     *
     * @throws IllegalArgumentException when the artifact is not a reviewed deferred-check host
     * @throws Exception when registration, retransformation or the match proof fails
     */
    synchronized void install() throws Exception {
        if (hook != null) return;
        if (!(VerifiedInstall.install(SPEC, context)
                instanceof VerifiedInstall.Attempt.Installed<DeferredGlErrorCheckTarget> installed)) {
            throw new IllegalStateException("deferred error check refused: host-not-supported-or-hook-disabled");
        }
        hook = installed.hook();
    }

    /** Returns the reviewed target description for the install marker log. */
    String targetDescription() {
        final DeferredGlErrorCheckTarget target = hook.contract();
        return target.errorOwner() + "." + DeferredGlErrorCheckTarget.ERROR_METHOD
                + DeferredGlErrorCheckTarget.ERROR_DESCRIPTOR + " + "
                + target.frameOwner() + "." + DeferredGlErrorCheckTarget.FRAME_METHOD
                + DeferredGlErrorCheckTarget.FRAME_DESCRIPTOR;
    }

    @Override
    public synchronized void close() {
        if (hook != null) hook.close();
    }

    boolean restored() {
        return hook != null && hook.restored();
    }
}
