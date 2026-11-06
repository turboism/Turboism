package dev.turboism.bootstrap;

import dev.turboism.config.RuntimeStartupConfig;

/**
 * Whether a verified hook's admission consults the launch startup policy, i.e. safe mode and the
 * operator's {@code hooks.disabledIds} kill switch.
 */
enum PolicyCheck {
    /**
     * The hook consults {@link RuntimeStartupConfig#hookEnabled}: safe mode and the kill switch refuse
     * it. A {@code null} policy refuses too, so a launch without a policy snapshot can never admit an
     * optimization.
     */
    REQUIRED,

    /**
     * The hook deliberately never consults the startup policy: its only switch is a system property or
     * a persisted preference, so production is gated by
     * {@code TurboismAgent.startupPolicyRefusal} and by the preference gate instead. Preserved exactly
     * for {@code inspectorPropertyBypass}, {@code modelInstanceCoalesce} and the test-only glGetError
     * experiment so a refactor cannot silently widen the kill switch.
     */
    SKIP;

    /**
     * @param policy the launch startup policy, tolerated {@code null} only for {@link #SKIP}
     * @param hookId the hook policy id
     * @return whether the policy half of the admission row admits the hook
     */
    boolean admits(final RuntimeStartupConfig policy, final String hookId) {
        return switch (this) {
            case REQUIRED -> policy != null && policy.hookEnabled(hookId);
            case SKIP -> true;
        };
    }
}
