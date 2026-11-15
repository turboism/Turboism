package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.paramrow.ParameterRowRepaintBridge;
import dev.turboism.adapter.cubism.optimization.paramrow.ParameterRowRepaintTarget;
import dev.turboism.adapter.cubism.optimization.paramrow.ParameterRowRepaintTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.io.IOException;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Installs and restores the verified Parameter Palette incremental row sync and repaint elision weave.
 */
final class VerifiedParameterRowRepaintInstaller implements AutoCloseable {

    static final String HOOK_ID = "cubism.ui.parameter-row-repaint";

    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);

    static final int EXPECTED_MATCHES = 1;

    static final int EXPECTED_OWNERS = 1;

    private static final String FEATURE_NAME = "parameter row repaint";
    private static final String SITES_PREFIX = "sites=";
    private static final String REFUSED_MESSAGE =
            "parameter row repaint refused: host-not-supported-or-hook-disabled";
    private static final String METHOD_SEPARATOR = ".";
    private static final String FIELD_SEPARATOR = ":";

    static final WeaveSpec<ParameterRowRepaintTarget> SPEC = new WeaveSpec<>(
            ADMISSION,
            FEATURE_NAME,
            VerifiedParameterRowRepaintInstaller::resolve,
            new WeavePlan<>(
                    VerifiedParameterRowRepaintInstaller::targets,
                    VerifiedParameterRowRepaintInstaller::sitesProven),
            (target, launch) -> {
                final ParameterRowRepaintBridge bridge =
                        new ParameterRowRepaintBridge(launch.hostLoader(), target);
                return BridgeLayer.of(bridge::install, bridge);
            },
            WeaveEffects.none());

    private final InstallContext context;
    private VerifiedInstall<ParameterRowRepaintTarget> hook;

    VerifiedParameterRowRepaintInstaller(final InstallContext context) {
        this.context = context;
    }

    static boolean admitted(
            final Path artifact, final RuntimeStartupConfig config, final boolean requested, final int jvm)
            throws IOException {
        return VerifiedInstall.admits(ADMISSION, config, requested, jvm)
                && ParameterRowRepaintTarget.of(HostArtifactDigest.cached(artifact))
                        .isPresent();
    }

    private static Optional<ParameterRowRepaintTarget> resolve(final Path artifact) throws Exception {
        return ParameterRowRepaintTarget.of(HostArtifactDigest.cached(artifact));
    }

    private static List<WeaveTarget> targets(
            final ParameterRowRepaintTarget target, final WeaveSource source) throws Exception {
        final Class<?> controllerOwner = source.load(target.controllerOwner());
        for (final ParameterRowRepaintTarget.MethodDep dep : target.methodDependencies()) {
            verifyMethodPresent(source.load(dep.owner()), dep.name(), dep.descriptor());
        }
        for (final ParameterRowRepaintTarget.FieldDep dep : target.fieldDependencies()) {
            verifyFieldPresent(source.load(dep.owner()), dep.name(), dep.descriptor());
        }
        final ParameterRowRepaintTransformer transformer = new ParameterRowRepaintTransformer(
                source.loader(), source.artifact(), source.referenceBytes(controllerOwner), target);
        return List.of(WeaveTarget.single(
                controllerOwner, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure));
    }

    private static String sitesProven(final List<WeaveTarget> targets) {
        final int sites = targets.get(0)
                .transformer(ParameterRowRepaintTransformer.class)
                .sites();
        return sites == ParameterRowRepaintTransformer.EXPECTED_SITES ? null : SITES_PREFIX + sites;
    }

    private static void verifyMethodPresent(final Class<?> owner, final String name, final String descriptor)
            throws NoSuchMethodException {
        if (matchesAnyMethod(owner.getMethods(), name, descriptor)
                || matchesAnyMethod(owner.getDeclaredMethods(), name, descriptor)) {
            return;
        }
        throw new NoSuchMethodException(owner.getName() + METHOD_SEPARATOR + name + descriptor);
    }

    private static boolean matchesAnyMethod(
            final Method[] methods, final String name, final String descriptor) {
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

    private static void verifyFieldPresent(final Class<?> owner, final String name, final String descriptor)
            throws NoSuchFieldException {
        for (final Field field : owner.getDeclaredFields()) {
            if (!field.getName().equals(name)) {
                continue;
            }
            if (field.getType().descriptorString().equals(descriptor)) {
                return;
            }
        }
        throw new NoSuchFieldException(
                owner.getName() + METHOD_SEPARATOR + name + FIELD_SEPARATOR + descriptor);
    }

    synchronized void install() throws Exception {
        if (hook != null) {
            return;
        }
        if (!(VerifiedInstall.install(SPEC, context)
                instanceof VerifiedInstall.Attempt.Installed<ParameterRowRepaintTarget> installed)) {
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
