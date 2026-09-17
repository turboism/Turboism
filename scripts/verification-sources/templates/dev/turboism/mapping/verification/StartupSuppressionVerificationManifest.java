package dev.turboism.mapping.verification;

/** Task-scoped runtime admission for exact-host 5.3.03 startup-suppression validation. */
public final class StartupSuppressionVerificationManifest {

    private static final String VALIDATION_PROPERTY =
        "turboism.validation.startupSuppression";
    private static final String VALIDATION_TOKEN =
        "EXACT_5303_STARTUP_SUPPRESSION_CANDIDATE";
    private static final String VALIDATION_HOST_VERSION_PROPERTY =
        "turboism.validation.hostVersion";
    private static final String VALIDATION_HOST_VERSION = "5303";
    private static final String VALIDATION_MODE_PROPERTY =
        "turboism.validation.startupSuppression.mode";
    private static final String VALIDATION_MODE = "splash-update-information";
    private static final String VALIDATION_RUN_ID_PROPERTY =
        "turboism.validation.runId";

    /**
     * Returns whether this JVM is the exact task-scoped 5.3.03 startup-suppression lane.
     *
     * <p>The installer still requires premain, the exact reviewed artifact digest, the exact
     * transformer profile, requested suppression policy, and an unloaded target class. This
     * identity opens only startup-suppression installation; broad runtime admission remains
     * closed.</p>
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

    private StartupSuppressionVerificationManifest() {
    }
}
