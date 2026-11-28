package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.HostMemberVerifier;
import dev.turboism.adapter.cubism.optimization.physics.PhysicsAccelerationBridge;
import dev.turboism.adapter.cubism.optimization.physics.PhysicsAccelerationTarget;
import dev.turboism.adapter.cubism.optimization.physics.PhysicsAccelerationTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Verified admission, dependency verification, bytecode weaving, and restoration for Cubism physics
 * simulation inner-loop acceleration.
 */
final class VerifiedPhysicsAccelerationInstaller implements AutoCloseable {

    static final String HOOK_ID = VerifiedWarpDeformEditAccelerationInstaller.HOOK_ID;

    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);

    static final int EXPECTED_MATCHES = PhysicsAccelerationTarget.EXPECTED_MATCHES;

    static final int EXPECTED_OWNERS = PhysicsAccelerationTarget.EXPECTED_OWNERS;

    private static final String FEATURE_NAME = "physics acceleration";
    private static final String REFUSED_MESSAGE = "physics acceleration refused: host-not-supported-or-hook-disabled";
    private static final String SITES_PREFIX = "sites=";

    private final InstallContext context;
    private PhysicsAccelerationBridge bridge;
    private VerifiedInstall<PhysicsAccelerationTarget> hook;

    VerifiedPhysicsAccelerationInstaller(final InstallContext context) {
        this.context = context;
    }

    static boolean admitted(final Path artifact, final RuntimeStartupConfig config, final boolean requested)
            throws IOException {
        return admitted(artifact, config, requested, Runtime.version().feature());
    }

    static boolean admitted(
            final Path artifact, final RuntimeStartupConfig config, final boolean requested, final int jvmFeature)
            throws IOException {
        if (artifact == null
                || !Files.isRegularFile(artifact)
                || !VerifiedInstall.admits(ADMISSION, config, requested, jvmFeature)) {
            return false;
        }
        return PhysicsAccelerationTarget.of(HostArtifactDigest.cached(artifact)).isPresent();
    }

    private static Optional<PhysicsAccelerationTarget> resolve(final Path artifact) throws Exception {
        if (artifact == null || !Files.isRegularFile(artifact)) {
            return Optional.empty();
        }
        return PhysicsAccelerationTarget.of(HostArtifactDigest.cached(artifact));
    }

    private WeaveSpec<PhysicsAccelerationTarget> spec() {
        return new WeaveSpec<>(
                ADMISSION,
                FEATURE_NAME,
                VerifiedPhysicsAccelerationInstaller::resolve,
                new WeavePlan<>(
                        VerifiedPhysicsAccelerationInstaller::targets,
                        VerifiedPhysicsAccelerationInstaller::sitesProven),
                (target, launch) -> {
                    bridge = new PhysicsAccelerationBridge();
                    return BridgeLayer.of(bridge::install, bridge);
                },
                WeaveEffects.none());
    }

    private static List<WeaveTarget> targets(final PhysicsAccelerationTarget target, final WeaveSource source)
            throws Exception {
        verifyDependencies(target, source);
        final Class<?> evaluatorClass = source.load(target.evaluatorOwner());
        final byte[] reference = source.referenceBytes(evaluatorClass);
        final PhysicsAccelerationTransformer transformer =
                new PhysicsAccelerationTransformer(source.loader(), source.artifact(), reference, target);
        return List.of(WeaveTarget.single(
                evaluatorClass, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure));
    }

    private static void verifyDependencies(final PhysicsAccelerationTarget target, final WeaveSource source)
            throws Exception {
        final String vecDesc = "()L" + PhysicsAccelerationTarget.VECTOR2_OWNER + ";";
        final String setVecDesc = "(L" + PhysicsAccelerationTarget.VECTOR2_OWNER + ";)V";
        final String arrayListDesc = "()L" + PhysicsAccelerationTarget.ARRAY_LIST_OWNER + ";";
        final String srcTypeDesc = "L" + PhysicsAccelerationTarget.SOURCE_TYPE_OWNER + ";";

        final Class<?> evalClass = source.load(target.evaluatorOwner());
        HostMemberVerifier.requireField(evalClass, "c", "L" + PhysicsAccelerationTarget.VECTOR2_OWNER + ";");
        HostMemberVerifier.requireField(evalClass, "d", "F");
        HostMemberVerifier.requireField(evalClass, "b", "F");
        HostMemberVerifier.requireField(evalClass, "e", "F");
        HostMemberVerifier.requireField(evalClass, "f", "Z");
        HostMemberVerifier.requireMethod(
                evalClass,
                PhysicsAccelerationTarget.TARGET_METHOD_NAME,
                PhysicsAccelerationTarget.APPLY_INPUT_DESCRIPTOR);
        HostMemberVerifier.requireMethod(
                evalClass,
                PhysicsAccelerationTarget.TARGET_METHOD_NAME,
                PhysicsAccelerationTarget.APPLY_OUTPUT_DESCRIPTOR);

        final Class<?> mathClass = source.load(target.mathUtilsOwner());
        HostMemberVerifier.requireField(mathClass, "a", "L" + target.mathUtilsOwner() + ";");
        HostMemberVerifier.requireMethod(mathClass, "b", "(FF)F");
        HostMemberVerifier.requireMethod(mathClass, "c", "()F");

        final Class<?> settingsClass = source.load(PhysicsAccelerationTarget.SETTINGS_SOURCE_OWNER);
        HostMemberVerifier.requireMethod(settingsClass, "getInputs", arrayListDesc);
        HostMemberVerifier.requireMethod(settingsClass, "getVertices", arrayListDesc);
        HostMemberVerifier.requireMethod(settingsClass, "getOutputs", arrayListDesc);
        HostMemberVerifier.requireMethod(settingsClass, "getNormalizedPositionValueMax", "()F");
        HostMemberVerifier.requireMethod(settingsClass, "getNormalizedPositionValueMin", "()F");
        HostMemberVerifier.requireMethod(settingsClass, "getNormalizedPositionDefaultValue", "()F");
        HostMemberVerifier.requireMethod(settingsClass, "getNormalizedAngleValueMax", "()F");
        HostMemberVerifier.requireMethod(settingsClass, "getNormalizedAngleValueMin", "()F");
        HostMemberVerifier.requireMethod(settingsClass, "getNormalizedAngleDefaultValue", "()F");
        HostMemberVerifier.requireMethod(settingsClass, "setTotalAngle", "(F)V");

        final Class<?> vertexClass = source.load(PhysicsAccelerationTarget.VERTEX_OWNER);
        HostMemberVerifier.requireMethod(vertexClass, "getPosition", vecDesc);
        HostMemberVerifier.requireMethod(vertexClass, "setPosition", setVecDesc);
        HostMemberVerifier.requireMethod(vertexClass, "getLocalPosition", vecDesc);
        HostMemberVerifier.requireMethod(vertexClass, "setLocalPosition", setVecDesc);
        HostMemberVerifier.requireMethod(vertexClass, "getOldPosition", vecDesc);
        HostMemberVerifier.requireMethod(vertexClass, "setOldPosition", setVecDesc);
        HostMemberVerifier.requireMethod(vertexClass, "getOldGravity", vecDesc);
        HostMemberVerifier.requireMethod(vertexClass, "setOldGravity", setVecDesc);
        HostMemberVerifier.requireMethod(vertexClass, "getVelocity", vecDesc);
        HostMemberVerifier.requireMethod(vertexClass, "setVelocity", setVecDesc);
        HostMemberVerifier.requireMethod(vertexClass, "getForce", vecDesc);
        HostMemberVerifier.requireMethod(vertexClass, "setForce", setVecDesc);
        HostMemberVerifier.requireMethod(vertexClass, "getRadius", "()F");
        HostMemberVerifier.requireMethod(vertexClass, "getAcceleration", "()F");
        HostMemberVerifier.requireMethod(vertexClass, "getDelay", "()F");
        HostMemberVerifier.requireMethod(vertexClass, "getMobility", "()F");

        final Class<?> inputClass = source.load(PhysicsAccelerationTarget.INPUT_OWNER);
        HostMemberVerifier.requireMethod(
                inputClass, "getSource", "()L" + PhysicsAccelerationTarget.PARAM_GUID_OWNER + ";");
        HostMemberVerifier.requireMethod(inputClass, "getWeight", "()F");
        HostMemberVerifier.requireMethod(inputClass, "getType", "()" + srcTypeDesc);
        HostMemberVerifier.requireMethod(inputClass, "isReverse", "()Z");

        final Class<?> outputClass = source.load(PhysicsAccelerationTarget.OUTPUT_OWNER);
        HostMemberVerifier.requireMethod(outputClass, "getVertexIndex", "()I");
        HostMemberVerifier.requireMethod(
                outputClass, "getDestination", "()L" + PhysicsAccelerationTarget.PARAM_GUID_OWNER + ";");
        HostMemberVerifier.requireMethod(
                outputClass, "getGuid", "()L" + PhysicsAccelerationTarget.PHYSICS_DATA_GUID_OWNER + ";");
        HostMemberVerifier.requireMethod(outputClass, "getType", "()" + srcTypeDesc);
        HostMemberVerifier.requireMethod(outputClass, "isReverse", "()Z");

        HostMemberVerifier.requireMethod(source.load(PhysicsAccelerationTarget.CONTROLLER_COMPANION_OWNER), "b", "()F");
        HostMemberVerifier.requireMethod(source.load(PhysicsAccelerationTarget.SOURCE_TYPE_OWNER), "ordinal", "()I");
        HostMemberVerifier.requireMethod(
                source.load(PhysicsAccelerationTarget.PARAMETER_SET_OWNER),
                "get",
                "(L" + PhysicsAccelerationTarget.PARAM_GUID_OWNER + ";)L" + PhysicsAccelerationTarget.PARAMETER_OWNER
                        + ";");
        final Class<?> paramClass = source.load(PhysicsAccelerationTarget.PARAMETER_OWNER);
        HostMemberVerifier.requireMethod(
                paramClass, "getId", "()L" + PhysicsAccelerationTarget.PARAMETER_ID_OWNER + ";");
        HostMemberVerifier.requireMethod(paramClass, "getValue", "()F");

        final Class<?> viewerClass = source.load(PhysicsAccelerationTarget.VIEWER_MASK_OWNER);
        HostMemberVerifier.requireMethod(viewerClass, "d", "(L" + PhysicsAccelerationTarget.PARAMETER_ID_OWNER + ";)Z");
        HostMemberVerifier.requireMethod(viewerClass, "e", "(L" + PhysicsAccelerationTarget.PARAMETER_ID_OWNER + ";)Z");

        final Class<?> vecClass = source.load(PhysicsAccelerationTarget.VECTOR2_OWNER);
        HostMemberVerifier.requireMethod(vecClass, "getX", "()F");
        HostMemberVerifier.requireMethod(vecClass, "getY", "()F");
        HostMemberVerifier.requireMethod(vecClass, "setX", "(F)V");
        HostMemberVerifier.requireMethod(vecClass, "setY", "(F)V");

        final Class<?> listClass = source.load(PhysicsAccelerationTarget.ARRAY_LIST_OWNER);
        HostMemberVerifier.requireMethod(listClass, "size", "()I");
        HostMemberVerifier.requireMethod(listClass, "get", "(I)Ljava/lang/Object;");
    }

    private static String sitesProven(final List<WeaveTarget> targets) {
        final PhysicsAccelerationTransformer transformer =
                targets.get(0).transformer(PhysicsAccelerationTransformer.class);
        final int actual = transformer.sites();
        final int expected = PhysicsAccelerationTarget.EXPECTED_TOTAL_SITES;
        return actual == expected ? null : SITES_PREFIX + actual + "/" + expected;
    }

    synchronized void install() throws Exception {
        if (hook != null) {
            return;
        }
        if (!(VerifiedInstall.install(spec(), context)
                instanceof VerifiedInstall.Attempt.Installed<PhysicsAccelerationTarget> installed)) {
            throw new IllegalStateException(REFUSED_MESSAGE);
        }
        hook = installed.hook();
    }

    boolean restored() {
        return hook != null && hook.restored();
    }

    @Override
    public synchronized void close() {
        if (hook != null) {
            hook.close();
        }
    }
}
