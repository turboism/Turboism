package dev.turboism.mapping.verification;

/** Task-scoped runtime admission for exact-host 5.3.03 FPS render-scene validation. */
public final class PerformanceFpsVerificationManifest {

    private static final String VALIDATION_PROPERTY =
        "turboism.validation.fps";
    private static final String VALIDATION_TOKEN =
        "EXACT_5303_FPS_RENDER_SCENE_CANDIDATE";
    private static final String VALIDATION_HOST_VERSION_PROPERTY =
        "turboism.validation.hostVersion";
    private static final String VALIDATION_HOST_VERSION = "5303";
    private static final String VALIDATION_MODE_PROPERTY =
        "turboism.validation.fps.mode";
    private static final String VALIDATION_MODE = "render-scene";
    private static final String VALIDATION_RUN_ID_PROPERTY =
        "turboism.validation.runId";

    /** Exact Editor version served by this validation-only admission. */
    public static final String CUBISM_VERSION_5_3_03 = "5.3.03";

    /**
     * Returns whether this JVM is the exact task-scoped 5.3.03 FPS render-scene lane.
     *
     * <p>The exact artifact digest and target tuple remain independently checked by the FPS hook
     * installer. This identity opens only hook publication for the public FPS sampling exerciser;
     * the full performance probe and broad runtime admission remain closed.</p>
     */
    public static boolean admits5303ValidationCandidate() {
        return admits5303ValidationCandidate(
            System.getProperty(VALIDATION_PROPERTY),
            System.getProperty(VALIDATION_HOST_VERSION_PROPERTY),
            System.getProperty(VALIDATION_MODE_PROPERTY),
            System.getProperty(VALIDATION_RUN_ID_PROPERTY)
        );
    }

    static boolean admits5303ValidationCandidate(
        final String token,
        final String hostVersion,
        final String mode,
        final String runId
    ) {
        return VALIDATION_TOKEN.equals(token)
            && VALIDATION_HOST_VERSION.equals(hostVersion)
            && VALIDATION_MODE.equals(mode)
            && runId != null
            && !runId.isBlank();
    }

    private PerformanceFpsVerificationManifest() {
    }
}
