package dev.turboism.adapter.cubism.startup;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

record StartupSuppressionProfile(
    String cubismVersion,
    HostArtifactDigest artifact,
    String targetOwner,
    MethodSelector startupMethod,
    MethodSelector updateCheckCall,
    MethodSelector informationCall,
    MethodSelector splashMethod
) {

    private static final String TARGET_OWNER = "com/live2d/cubism/CECubismEditorApp";
    private static final String APP_CONTROLLER_OWNER = "com/live2d/cubism/CEAppCtrl";
    private static final HostArtifactDigest CUBISM_52_ARTIFACT = ReviewedHostArtifacts.CUBISM_5_2_03;
    private static final HostArtifactDigest CUBISM_53_ARTIFACT = ReviewedHostArtifacts.CUBISM_5_3_02;
    private static final HostArtifactDigest CUBISM_5303_ARTIFACT = ReviewedHostArtifacts.CUBISM_5_3_03;

    StartupSuppressionProfile {
        Objects.requireNonNull(cubismVersion, "cubismVersion");
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(targetOwner, "targetOwner");
        Objects.requireNonNull(startupMethod, "startupMethod");
        Objects.requireNonNull(updateCheckCall, "updateCheckCall");
        Objects.requireNonNull(informationCall, "informationCall");
        Objects.requireNonNull(splashMethod, "splashMethod");
    }

    static Optional<StartupSuppressionProfile> forArtifact(final HostArtifactDigest artifact) {
        Objects.requireNonNull(artifact, "artifact");
        if (CUBISM_52_ARTIFACT.equals(artifact)) {
            return Optional.of(profile("5.2.03", artifact, "()Lcom/live2d/ui/window/X;"));
        }
        if (CUBISM_53_ARTIFACT.equals(artifact)) {
            return Optional.of(profile("5.3.02", artifact, "()Lcom/live2d/ui/window/V;"));
        }
        if (CUBISM_5303_ARTIFACT.equals(artifact)) {
            return Optional.of(profile("5.3.03", artifact, "()Lcom/live2d/ui/window/V;"));
        }
        return Optional.empty();
    }

    /**
     * Returns the reviewed suppression profile for a declared reviewed version,
     * carrying the artifact's own observed digest — never a reviewed digest — so
     * downstream evidence stays honest for repackaged hosts. Callers must still
     * verify {@link #reviewedClassSha256()} pins against the actual artifact.
     */
    static Optional<StartupSuppressionProfile> forReviewedVersion(
        final String version,
        final HostArtifactDigest observedArtifact
    ) {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(observedArtifact, "observedArtifact");
        if (ReviewedHostArtifacts.CUBISM_5_2_03_VERSION.equals(version)) {
            return Optional.of(profile("5.2.03", observedArtifact, "()Lcom/live2d/ui/window/X;"));
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_02_VERSION.equals(version)) {
            return Optional.of(profile("5.3.02", observedArtifact, "()Lcom/live2d/ui/window/V;"));
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_03_VERSION.equals(version)) {
            return Optional.of(profile("5.3.03", observedArtifact, "()Lcom/live2d/ui/window/V;"));
        }
        return Optional.empty();
    }

    /**
     * SHA-256 of the two host classes the transformer rewrites, keyed by internal
     * name, per reviewed generation. A repackaged or differently-declared artifact
     * is admissible only when exactly one generation's pins verify.
     */
    static Map<String, Map<String, String>> reviewedClassSha256() {
        return REVIEWED_CLASS_SHA256;
    }

    private static final Map<String, Map<String, String>> REVIEWED_CLASS_SHA256 = Map.of(
        "5.2.03", Map.of(
            TARGET_OWNER, "864dd2f505e43eb04f77748f36879373fd23ad32e7b70246869928129e2c3408",
            APP_CONTROLLER_OWNER, "6762f7d5bb593648f54cc8cf834e71d788fa8b0c7c5621ee1827a30ac18980f7"),
        "5.3.02", Map.of(
            TARGET_OWNER, "87cf7868689efdf6bd438fc434dd6b7d2cc2b789d2538b80f71b56b08e5b134f",
            APP_CONTROLLER_OWNER, "ed2ed37d5d3f34375aa8918c12305c61be6e6e78bfc7e28c382c72b2e04215fc"),
        "5.3.03", Map.of(
            TARGET_OWNER, "ef1fd0a837de1f3c3140b4b7a5a36b7fd499ae7fca2a9c7f2170849564009a15",
            APP_CONTROLLER_OWNER, "a4613396fdf86de9b9ba6ca9950b2bf7748dd7ae5f8d85ea142968331bca2b2e"));

    private static StartupSuppressionProfile profile(
        final String version,
        final HostArtifactDigest artifact,
        final String splashDescriptor
    ) {
        return new StartupSuppressionProfile(
            version,
            artifact,
            TARGET_OWNER,
            new MethodSelector(TARGET_OWNER, "a", "([Ljava/lang/String;)V"),
            new MethodSelector(APP_CONTROLLER_OWNER, "command_checkUpdate", "()V"),
            new MethodSelector(APP_CONTROLLER_OWNER, "showInformation", "()V"),
            new MethodSelector(TARGET_OWNER, "e", splashDescriptor)
        );
    }

    record MethodSelector(String owner, String name, String descriptor) {
        MethodSelector {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }
}
