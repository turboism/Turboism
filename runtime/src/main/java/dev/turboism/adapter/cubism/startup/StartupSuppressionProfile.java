package dev.turboism.adapter.cubism.startup;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;

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
