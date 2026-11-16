package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.HostMemberVerifier;
import dev.turboism.adapter.cubism.optimization.hittest.MeshHitTestTarget;
import dev.turboism.adapter.cubism.optimization.hittest.MeshHitTestTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Installs and restores the verified allocation-free mesh bounding-box hit-test weave.
 */
final class VerifiedMeshHitTestInstaller implements VerifiedHookInstaller {

    static final String HOOK_ID = "cubism.mesh.hit-test";

    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.ANY);

    static final int EXPECTED_MATCHES = 1;

    static final int EXPECTED_OWNERS = 1;

    private static final String FEATURE_NAME = "mesh hit test";
    private static final String SITES_PREFIX = "sites=";
    private static final String REFUSED_MESSAGE = "mesh hit test refused: host-not-supported-or-hook-disabled";
    private static final String SUBCLASS_ERROR_PREFIX = "unexpected mesh subclass contract: ";

    static final WeaveSpec<MeshHitTestTarget> SPEC = new WeaveSpec<>(
            ADMISSION,
            FEATURE_NAME,
            VerifiedMeshHitTestInstaller::resolve,
            new WeavePlan<>(VerifiedMeshHitTestInstaller::targets, VerifiedMeshHitTestInstaller::sitesProven),
            BridgeFactory.none(),
            WeaveEffects.none());

    private final InstallContext context;
    private VerifiedInstall<MeshHitTestTarget> hook;

    VerifiedMeshHitTestInstaller(final InstallContext context) {
        this.context = context;
    }

    static boolean admitted(final Path artifact, final RuntimeStartupConfig config, final boolean requested)
            throws IOException {
        return VerifiedInstall.admits(
                        ADMISSION, config, requested, Runtime.version().feature())
                && MeshHitTestTarget.of(HostArtifactDigest.cached(artifact)).isPresent();
    }

    private static Optional<MeshHitTestTarget> resolve(final Path artifact) throws Exception {
        return MeshHitTestTarget.of(HostArtifactDigest.cached(artifact));
    }

    private static List<WeaveTarget> targets(final MeshHitTestTarget target, final WeaveSource source)
            throws Exception {
        final Class<?> owner = source.load(MeshHitTestTarget.OWNER);
        final Class<?> gmesh2 = source.load(MeshHitTestTarget.GMESH2_OWNER);
        final Class<?> gmesh3 = source.load(MeshHitTestTarget.GMESH3_OWNER);
        final Class<?> bounds = source.load(MeshHitTestTarget.BOUNDS_OWNER);
        verifyFinalSubclass(gmesh2, owner);
        verifyFinalSubclass(gmesh3, owner);
        for (final MeshHitTestTarget.MethodDep dep : target.methodDependencies()) {
            HostMemberVerifier.requireMethod(source.load(dep.owner()), dep.name(), dep.descriptor());
        }
        final byte[] ownerBytes = source.referenceBytes(owner);
        final byte[] gmesh2Bytes = source.referenceBytes(gmesh2);
        final byte[] gmesh3Bytes = source.referenceBytes(gmesh3);
        final byte[] boundsBytes = source.referenceBytes(bounds);
        final String helperOwner = MeshHitTestTransformer.resolveBoundsHelperOwner(boundsBytes);
        final Class<?> helper = source.load(helperOwner);
        final byte[] helperBytes = source.referenceBytes(helper);
        final MeshHitTestTransformer transformer = new MeshHitTestTransformer(
                source.loader(),
                source.artifact(),
                ownerBytes,
                gmesh2Bytes,
                gmesh3Bytes,
                boundsBytes,
                helperBytes,
                target);
        return List.of(
                WeaveTarget.single(owner, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure));
    }

    private static void verifyFinalSubclass(final Class<?> subclass, final Class<?> expectedSuper) {
        if (subclass.getSuperclass() != expectedSuper || !Modifier.isFinal(subclass.getModifiers())) {
            throw new IllegalStateException(SUBCLASS_ERROR_PREFIX + subclass.getName());
        }
    }

    private static String sitesProven(final List<WeaveTarget> targets) {
        final int sites =
                targets.get(0).transformer(MeshHitTestTransformer.class).sites();
        return sites == MeshHitTestTransformer.EXPECTED_SITES ? null : SITES_PREFIX + sites;
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
                instanceof VerifiedInstall.Attempt.Installed<MeshHitTestTarget> installed)) {
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
