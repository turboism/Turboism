package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.HostMemberVerifier;
import dev.turboism.adapter.cubism.optimization.extinterpsibling.ExtendedInterpolationSiblingTarget;
import dev.turboism.adapter.cubism.optimization.extinterpsibling.ExtendedInterpolationSiblingTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Installs and restores the verified sibling extended-interpolation update elision weave.
 */
final class VerifiedExtendedInterpolationSiblingInstaller implements VerifiedHookInstaller {

    static final String HOOK_ID = "cubism.editor-model.extended-interpolation-sibling-elision";

    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.ANY);

    static final int EXPECTED_MATCHES = 1;

    static final int EXPECTED_OWNERS = 1;

    private static final String FEATURE_NAME = "sibling extended-interpolation elision";
    private static final String SITES_PREFIX = "sites=";
    private static final String REFUSED_MESSAGE =
            "sibling extended-interpolation elision refused: host-not-supported-or-hook-disabled";

    static final WeaveSpec<ExtendedInterpolationSiblingTarget> SPEC = new WeaveSpec<>(
            ADMISSION,
            FEATURE_NAME,
            VerifiedExtendedInterpolationSiblingInstaller::resolve,
            new WeavePlan<>(
                    VerifiedExtendedInterpolationSiblingInstaller::targets,
                    VerifiedExtendedInterpolationSiblingInstaller::sitesProven),
            BridgeFactory.none(),
            WeaveEffects.none());

    private final InstallContext context;
    private VerifiedInstall<ExtendedInterpolationSiblingTarget> hook;

    VerifiedExtendedInterpolationSiblingInstaller(final InstallContext context) {
        this.context = context;
    }

    static boolean admitted(final Path artifact, final RuntimeStartupConfig config, final boolean requested)
            throws IOException {
        return VerifiedInstall.admits(
                        ADMISSION, config, requested, Runtime.version().feature())
                && ExtendedInterpolationSiblingTarget.of(HostArtifactDigest.cached(artifact))
                        .isPresent();
    }

    private static Optional<ExtendedInterpolationSiblingTarget> resolve(final Path artifact) throws Exception {
        return ExtendedInterpolationSiblingTarget.of(HostArtifactDigest.cached(artifact));
    }

    private static List<WeaveTarget> targets(final ExtendedInterpolationSiblingTarget target, final WeaveSource source)
            throws Exception {
        final Class<?> owner = source.load(ExtendedInterpolationSiblingTarget.OWNER);
        source.load(ExtendedInterpolationSiblingTarget.GUID_OWNER);
        for (final ExtendedInterpolationSiblingTarget.MethodDep dep : target.methodDependencies()) {
            HostMemberVerifier.requireMethod(source.load(dep.owner()), dep.name(), dep.descriptor());
        }
        final ExtendedInterpolationSiblingTransformer transformer = new ExtendedInterpolationSiblingTransformer(
                source.loader(), source.artifact(), source.referenceBytes(owner), target);
        return List.of(
                WeaveTarget.single(owner, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure));
    }

    private static String sitesProven(final List<WeaveTarget> targets) {
        final int sites = targets.get(0)
                .transformer(ExtendedInterpolationSiblingTransformer.class)
                .sites();
        return sites == ExtendedInterpolationSiblingTransformer.EXPECTED_SITES ? null : SITES_PREFIX + sites;
    }

    @Override
    public boolean admits(final InstallContext context) {
        try {
            return admitted(context.artifact(), context.policy(), true);
        } catch (final java.io.IOException unreadable) {
            return false;
        }
    }

    @Override
    public synchronized void install() throws Exception {
        if (hook != null) {
            return;
        }
        if (!(VerifiedInstall.install(SPEC, context)
                instanceof VerifiedInstall.Attempt.Installed<ExtendedInterpolationSiblingTarget> installed)) {
            throw new IllegalStateException(REFUSED_MESSAGE);
        }
        hook = installed.hook();
    }

    @Override
    public synchronized void close() {
        if (hook != null) {
            hook.close();
        }
    }

    boolean restored() {
        return hook != null && hook.restored();
    }
}
