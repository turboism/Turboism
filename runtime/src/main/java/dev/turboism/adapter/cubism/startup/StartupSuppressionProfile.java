package dev.turboism.adapter.cubism.startup;

import dev.turboism.adapter.cubism.optimization.ClassPinTable;
import dev.turboism.internal.ReviewedVersions;
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
        Optional<MethodSelector> informationCall,
        MethodSelector splashMethod) {

    private static final String TARGET_OWNER = "com/live2d/cubism/CECubismEditorApp";
    private static final String APP_CONTROLLER_OWNER = "com/live2d/cubism/CEAppCtrl";
    private static final String STARTUP_DESCRIPTOR = "([Ljava/lang/String;)V";
    private static final String SPLASH_Y_DESCRIPTOR = "()Lcom/live2d/ui/window/Y;";
    private static final String SPLASH_X_DESCRIPTOR = "()Lcom/live2d/ui/window/X;";
    private static final String SPLASH_V_DESCRIPTOR = "()Lcom/live2d/ui/window/V;";
    private static final MethodSelector UPDATE_CHECK_CALL =
            new MethodSelector(APP_CONTROLLER_OWNER, "command_checkUpdate", "()V");

    /**
     * Canonical {@code CEAppCtrl.showInformation()V} target, present on every reviewed
     * generation except 5.0.x — the transformer still counts it there so an
     * unexpected invocation fails closed instead of surviving untracked.
     */
    static final MethodSelector INFORMATION_CALL = new MethodSelector(APP_CONTROLLER_OWNER, "showInformation", "()V");

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
        return ReviewedHostArtifacts.cubismVersionOf(artifact)
                .flatMap(version -> forReviewedVersion(version, artifact));
    }

    /**
     * Returns the reviewed suppression profile for a declared reviewed version,
     * carrying the artifact's own observed digest — never a reviewed digest — so
     * downstream evidence stays honest for repackaged hosts. Callers must still
     * verify {@link #reviewedClassSha256()} pins against the actual artifact.
     */
    static Optional<StartupSuppressionProfile> forReviewedVersion(
            final String version, final HostArtifactDigest observedArtifact) {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(observedArtifact, "observedArtifact");
        final SuppressionShape shape = SHAPES.get(version);
        if (shape == null) {
            return Optional.empty();
        }
        return Optional.of(new StartupSuppressionProfile(
                version,
                observedArtifact,
                TARGET_OWNER,
                new MethodSelector(TARGET_OWNER, "a", STARTUP_DESCRIPTOR),
                UPDATE_CHECK_CALL,
                shape.informationCall() ? Optional.of(INFORMATION_CALL) : Optional.empty(),
                new MethodSelector(TARGET_OWNER, shape.splashName(), shape.splashDescriptor())));
    }

    /**
     * SHA-256 of the two host classes the transformer rewrites, keyed by internal
     * name, per reviewed generation. A repackaged or differently-declared artifact
     * is admissible only when exactly one generation's pins verify.
     */
    static Map<String, Map<String, String>> reviewedClassSha256() {
        return REVIEWED_CLASS_SHA256;
    }

    /** The reviewed Cubism versions carrying an admitted suppression profile. */
    static java.util.Set<String> reviewedCubismVersions() {
        return SHAPES.keySet();
    }

    private static final Map<String, Map<String, String>> REVIEWED_CLASS_SHA256 =
            ClassPinTable.load("startup-suppression");

    /**
     * The reviewed suppression shape per admitted generation: the splash-factory
     * selector and whether the startup entry invokes {@code CEAppCtrl.showInformation}.
     * Every tuple is the VERIFIED_STATIC contract of
     * {@code build/generated/verification/cubism-<version>-startup-suppression.json};
     * the startup entry {@code a([Ljava/lang/String;)V} and the update-check call
     * {@code CEAppCtrl.command_checkUpdate()V} are identical on all reviewed
     * generations. On 5.0.x the startup entry never invokes an information
     * dialog (CEAppCtrl has no such member), so those generations carry no
     * information selector and the skip is vacuously satisfied.
     */
    private static final Map<String, SuppressionShape> SHAPES = java.util.stream.Stream.of(
                    Map.entry(
                            ReviewedVersions.between("5.0.00", "5.0.02"),
                            new SuppressionShape("c", SPLASH_Y_DESCRIPTOR, false)),
                    Map.entry(
                            ReviewedVersions.between("5.0.03", "5.0.06"),
                            new SuppressionShape("e", SPLASH_X_DESCRIPTOR, false)),
                    Map.entry(
                            ReviewedVersions.between("5.1.00", "5.2.03"),
                            new SuppressionShape("e", SPLASH_X_DESCRIPTOR, true)),
                    Map.entry(ReviewedVersions.since("5.3.00"), new SuppressionShape("e", SPLASH_V_DESCRIPTOR, true)))
            .flatMap(entry -> entry.getKey().stream().map(version -> Map.entry(version, entry.getValue())))
            .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

    private record SuppressionShape(String splashName, String splashDescriptor, boolean informationCall) {
        private SuppressionShape {
            Objects.requireNonNull(splashName, "splashName");
            Objects.requireNonNull(splashDescriptor, "splashDescriptor");
        }
    }

    record MethodSelector(String owner, String name, String descriptor) {
        MethodSelector {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }
}
