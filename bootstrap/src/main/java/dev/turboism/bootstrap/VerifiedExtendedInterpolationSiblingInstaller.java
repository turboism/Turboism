package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.extinterpsibling.ExtendedInterpolationSiblingTarget;
import dev.turboism.adapter.cubism.optimization.extinterpsibling.ExtendedInterpolationSiblingTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.io.IOException;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Installs and restores the verified sibling extended-interpolation update elision weave.
 */
final class VerifiedExtendedInterpolationSiblingInstaller implements AutoCloseable {

    static final String HOOK_ID = "cubism.editor-model.extended-interpolation-sibling-elision";

    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.ANY);

    static final int EXPECTED_MATCHES = 1;

    static final int EXPECTED_OWNERS = 1;

    private static final String FEATURE_NAME = "sibling extended-interpolation elision";
    private static final String SITES_PREFIX = "sites=";
    private static final String REFUSED_MESSAGE =
            "sibling extended-interpolation elision refused: host-not-supported-or-hook-disabled";
    private static final String METHOD_SEPARATOR = ".";

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
        return VerifiedInstall.admits(ADMISSION, config, requested, Runtime.version().feature())
                && ExtendedInterpolationSiblingTarget.of(HostArtifactDigest.cached(artifact))
                        .isPresent();
    }

    private static Optional<ExtendedInterpolationSiblingTarget> resolve(final Path artifact) throws Exception {
        return ExtendedInterpolationSiblingTarget.of(HostArtifactDigest.cached(artifact));
    }

    private static List<WeaveTarget> targets(
            final ExtendedInterpolationSiblingTarget target, final WeaveSource source) throws Exception {
        final Class<?> owner = source.load(ExtendedInterpolationSiblingTarget.OWNER);
        source.load(ExtendedInterpolationSiblingTarget.GUID_OWNER);
        for (final ExtendedInterpolationSiblingTarget.MethodDep dep : target.methodDependencies()) {
            verifyMethodPresent(source.load(dep.owner()), dep.name(), dep.descriptor());
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

    private static void verifyMethodPresent(final Class<?> owner, final String name, final String descriptor)
            throws NoSuchMethodException {
        if (matchesAnyMethod(owner.getMethods(), name, descriptor)
                || matchesAnyMethod(owner.getDeclaredMethods(), name, descriptor)) {
            return;
        }
        throw new NoSuchMethodException(owner.getName() + METHOD_SEPARATOR + name + descriptor);
    }

    private static boolean matchesAnyMethod(final Method[] methods, final String name, final String descriptor) {
        for (final Method method : methods) {
            if (!method.getName().equals(name)) {
                continue;
            }
            final String actual = MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                    .descriptorString();
            if (actual.equals(descriptor)) {
                return true;
            }
        }
        return false;
    }

    synchronized void install() throws Exception {
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
