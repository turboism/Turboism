package dev.turboism.mapping.verification;

/** Task-scoped runtime admission for exact-host 5.3.03 project lifecycle validation. */
public final class ProjectLifecycleVerificationManifest {

    public static final String CUBISM_VERSION_5_3_03 = "5.3.03";

    private static final String VALIDATION_PROPERTY =
        "turboism.validation.projectLifecycle";
    private static final String VALIDATION_TOKEN =
        "EXACT_5303_PROJECT_LIFECYCLE_HOOK_CANDIDATE";
    private static final String VALIDATION_HOST_VERSION_PROPERTY =
        "turboism.validation.hostVersion";
    private static final String VALIDATION_HOST_VERSION = "5303";
    private static final String VALIDATION_MODE_PROPERTY =
        "turboism.projectLifecycleValidation.mode";
    private static final String VALIDATION_MODE = "project-lifecycle-hook-5303";
    private static final String VALIDATION_RUN_ID_PROPERTY =
        "turboism.validation.runId";

    /** Returns whether this JVM is the exact task-scoped 5.3.03 project lifecycle lane. */
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

    private ProjectLifecycleVerificationManifest() {
    }
}
