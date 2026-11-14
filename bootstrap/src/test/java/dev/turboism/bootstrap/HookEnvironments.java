package dev.turboism.bootstrap;

/**
 * Admission environments for hook contributor tests.
 *
 * <p>{@link HookEnvironment} is package-private, so this helper lives beside the production type.
 * It states only the admission facts a contributor may read: the host profile and whether the
 * agent granted full runtime admission.
 */
final class HookEnvironments {

    /** Host profile the reviewed Cubism build reports when a test does not pin one. */
    static final String REVIEWED_PROFILE = "5.3.03";

    private HookEnvironments() {}

    /** Returns an environment for the reviewed host profile. */
    static HookEnvironment admitted(final boolean admitted) {
        return admitted(REVIEWED_PROFILE, admitted);
    }

    /** Returns an environment with an explicit host profile and admission verdict. */
    static HookEnvironment admitted(final String profile, final boolean admitted) {
        return HookEnvironment.builder()
                .profile(profile)
                .fullRuntimeAdmission(admitted)
                .build();
    }
}
