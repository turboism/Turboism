package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.HostMemberVerifier;
import dev.turboism.adapter.cubism.optimization.animation.AnimationAccelerationBridge;
import dev.turboism.adapter.cubism.optimization.animation.AnimationAccelerationTarget;
import dev.turboism.adapter.cubism.optimization.animation.AnimationAccelerationTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Verified admission, dependency verification, bytecode weaving, and restoration for animation
 * timeline evaluation, formatting caches, and Graph Editor viewport culling.
 */
final class VerifiedAnimationAccelerationInstaller implements AutoCloseable {

    static final String HOOK_ID = VerifiedWarpDeformEditAccelerationInstaller.HOOK_ID;

    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);

    private static final String FEATURE_NAME = "animation acceleration";
    private static final String REFUSED_MESSAGE = "animation acceleration refused: host-not-supported-or-hook-disabled";
    private static final String SITES_PREFIX = "sites=";

    private final InstallContext context;
    private AnimationAccelerationBridge bridge;
    private VerifiedInstall<AnimationAccelerationTarget> hook;

    VerifiedAnimationAccelerationInstaller(final InstallContext context) {
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
        return AnimationAccelerationTarget.of(HostArtifactDigest.cached(artifact))
                .isPresent();
    }

    private static Optional<AnimationAccelerationTarget> resolve(final Path artifact) throws Exception {
        if (artifact == null || !Files.isRegularFile(artifact)) {
            return Optional.empty();
        }
        return AnimationAccelerationTarget.of(HostArtifactDigest.cached(artifact));
    }

    private WeaveSpec<AnimationAccelerationTarget> spec() {
        return new WeaveSpec<>(
                ADMISSION,
                FEATURE_NAME,
                VerifiedAnimationAccelerationInstaller::resolve,
                new WeavePlan<>(
                        VerifiedAnimationAccelerationInstaller::targets,
                        VerifiedAnimationAccelerationInstaller::sitesProven),
                (target, launch) -> {
                    bridge = new AnimationAccelerationBridge(launch.hostLoader(), target);
                    return BridgeLayer.of(bridge::install, bridge);
                },
                WeaveEffects.none());
    }

    private static List<WeaveTarget> targets(final AnimationAccelerationTarget target, final WeaveSource source)
            throws Exception {
        verifyDependencies(target, source);
        final List<String> owners = target.rewrittenOwners();
        final List<Class<?>> loadedClasses = new ArrayList<>(owners.size());
        final Map<String, byte[]> references = new LinkedHashMap<>();
        for (final String internalName : owners) {
            final Class<?> loaded = source.load(internalName);
            loadedClasses.add(loaded);
            references.put(internalName, source.referenceBytes(loaded));
        }
        final AnimationAccelerationTransformer transformer =
                new AnimationAccelerationTransformer(source.loader(), source.artifact(), references, target);
        final List<WeaveTarget> weaveTargets = new ArrayList<>(loadedClasses.size());
        for (int i = 0; i < loadedClasses.size(); i++) {
            final String internalName = owners.get(i);
            final Class<?> loaded = loadedClasses.get(i);
            weaveTargets.add(WeaveTarget.single(
                    loaded,
                    transformer,
                    transformer.expectedMatchesFor(internalName),
                    () -> transformer.matchesFor(internalName),
                    transformer::failure));
        }
        return weaveTargets;
    }

    private static void verifyDependencies(final AnimationAccelerationTarget target, final WeaveSource source)
            throws Exception {
        source.load(AnimationAccelerationTarget.FRAME_TIME_OWNER);
        HostMemberVerifier.requireMethod(source.load(target.trackContextOwner()), "g", "()Z");

        HostMemberVerifier.requireField(
                source.load(AnimationAccelerationTarget.TRACK_GROUP_INSTANCE_OWNER),
                "trackChildren",
                "L" + AnimationAccelerationTarget.ARRAY_LIST_OWNER + ";");

        final Class<?> movieInfoClass = source.load(AnimationAccelerationTarget.MOVIE_INFO_OWNER);
        HostMemberVerifier.requireMethod(movieInfoClass, "getDisplayTimeSubFrame", "(I)I");
        HostMemberVerifier.requireMethod(movieInfoClass, "getFpsInt", "()I");
        HostMemberVerifier.requireMethod(movieInfoClass, "getDisplayTimeSec", "(I)I");
        HostMemberVerifier.requireMethod(movieInfoClass, "getDisplayFrame", "(I)I");

        final Class<?> graphClass = source.load(AnimationAccelerationTarget.GRAPH_EDITOR_OWNER);
        HostMemberVerifier.requireField(
                graphClass, "paintComp", "L" + AnimationAccelerationTarget.CUSTOM_PAINT_COMP_OWNER + ";");
        HostMemberVerifier.requireField(graphClass, "df", "Ljava/text/DecimalFormat;");
        HostMemberVerifier.requireField(
                graphClass, "color_BezierHandle", "L" + AnimationAccelerationTarget.CCOLOR_OWNER + ";");
        HostMemberVerifier.requireField(
                graphClass, "color_selectedDot", "L" + AnimationAccelerationTarget.CCOLOR_OWNER + ";");
        HostMemberVerifier.requireField(graphClass, "enableShowKeyframesValue", "Z");
        HostMemberVerifier.requireField(graphClass, "enableDisplayAttributeName", "Z");
        HostMemberVerifier.requireMethod(graphClass, "getTimelineX", "(I)F");
        HostMemberVerifier.requireMethod(graphClass, "getTimelineY", "(D)F");
        HostMemberVerifier.requireMethod(
                graphClass,
                "calcBezierController",
                "([FL" + AnimationAccelerationTarget.BEZIER_PT_OWNER + ";L"
                        + AnimationAccelerationTarget.BEZIER_PT_OWNER + ";)V");
        HostMemberVerifier.requireMethod(
                graphClass,
                "overlappingPointsGuard",
                "(L" + AnimationAccelerationTarget.GRAPH_HANDLE_OWNER + ";)L"
                        + AnimationAccelerationTarget.GRAPH_HANDLE_OWNER + ";");

        HostMemberVerifier.requireMethod(
                source.load(AnimationAccelerationTarget.MUTABLE_SEQUENCE_OWNER),
                "getCurveTypes",
                "()L" + AnimationAccelerationTarget.ARRAY_LIST_OWNER + ";");
        final Class<?> bezierPtClass = source.load(AnimationAccelerationTarget.BEZIER_PT_OWNER);
        HostMemberVerifier.requireMethod(bezierPtClass, "getPos", "()I");
        HostMemberVerifier.requireMethod(
                bezierPtClass, "getAnchor$cubism", "()L" + AnimationAccelerationTarget.SEQ_PT_OWNER + ";");
        HostMemberVerifier.requireMethod(
                source.load(AnimationAccelerationTarget.SEQ_PT_OWNER), "getDoubleValue", "()D");
        HostMemberVerifier.requireField(source.load(AnimationAccelerationTarget.GRAPH_SWITCH_MAP_OWNER), "a", "[I");

        final Class<?> handleClass = source.load(AnimationAccelerationTarget.GRAPH_HANDLE_OWNER);
        HostMemberVerifier.requireMethod(handleClass, "b", "()F");
        HostMemberVerifier.requireMethod(handleClass, "c", "()F");
        HostMemberVerifier.requireMethod(handleClass, "d", "()F");
        HostMemberVerifier.requireMethod(handleClass, "e", "()F");

        final Class<?> arrayListClass = source.load(AnimationAccelerationTarget.ARRAY_LIST_OWNER);
        HostMemberVerifier.requireMethod(arrayListClass, "size", "()I");
        HostMemberVerifier.requireMethod(arrayListClass, "get", "(I)Ljava/lang/Object;");
    }

    private static String sitesProven(final List<WeaveTarget> targets) {
        final AnimationAccelerationTransformer transformer =
                targets.get(0).transformer(AnimationAccelerationTransformer.class);
        final int actual = transformer.sites();
        final int expected = transformer.expectedTotalSites();
        return actual == expected ? null : SITES_PREFIX + actual + "/" + expected;
    }

    synchronized void install() throws Exception {
        if (hook != null) {
            return;
        }
        if (!(VerifiedInstall.install(spec(), context)
                instanceof VerifiedInstall.Attempt.Installed<AnimationAccelerationTarget> installed)) {
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
