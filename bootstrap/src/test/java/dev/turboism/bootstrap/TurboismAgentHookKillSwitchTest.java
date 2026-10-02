package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.turboism.config.RuntimeStartupConfig;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The agent's central startup-policy gate ({@link TurboismAgent#startupPolicyRefusal})
 * covers every contributor the manifest registers: the {@code hooks.disabledIds}
 * kill switch always skips installation, and safe mode skips every contributor
 * not marked {@link HookContributor#requiredInSafeMode()}.
 */
final class TurboismAgentHookKillSwitchTest {

    private static List<HookContributor> manifestContributors() throws Exception {
        return HookManifest.load(TurboismAgentHookKillSwitchTest.class.getClassLoader());
    }

    private static RuntimeStartupConfig policy(final boolean safeMode, final Set<String> disabledIds) {
        return new RuntimeStartupConfig(safeMode, false, false, false, false, false, false, disabledIds);
    }

    private static HookEnvironment environment(final RuntimeStartupConfig policy) {
        return HookEnvironment.builder().startupPolicy(policy).build();
    }

    @Test
    void everyContributorIsSkippedWhenItsPolicyIdIsDisabled() throws Exception {
        for (final HookContributor contributor : manifestContributors()) {
            final HookEnvironment environment = environment(policy(false, Set.of(contributor.policyId())));
            assertNotNull(
                    TurboismAgent.startupPolicyRefusal(contributor, environment),
                    contributor.id() + " must honour hooks.disabledIds=" + contributor.policyId());
        }
    }

    @Test
    void disabledContributorNeverReachesInstall() throws Exception {
        // installPhaseHook refuses before admitted() and routes the skip through
        // the same capability-withdrawal path as an installation failure.
        final var installPhaseHook = TurboismAgent.class.getDeclaredMethod(
                "installPhaseHook", HookContributor.class, HookEnvironment.class);
        installPhaseHook.setAccessible(true);
        for (final HookContributor contributor : manifestContributors()) {
            final HookEnvironment environment = environment(policy(false, Set.of(contributor.policyId())));
            assertFalse(
                    (Boolean) installPhaseHook.invoke(null, contributor, environment),
                    contributor.id() + " must not install while disabled");
        }
    }

    @Test
    void safeModeSkipsEveryNonEssentialContributor() throws Exception {
        for (final HookContributor contributor : manifestContributors()) {
            final HookEnvironment environment = environment(policy(true, Set.of()));
            if (contributor.requiredInSafeMode()) {
                assertNull(
                        TurboismAgent.startupPolicyRefusal(contributor, environment),
                        contributor.id() + " is required in safe mode");
            } else {
                assertNotNull(
                        TurboismAgent.startupPolicyRefusal(contributor, environment),
                        contributor.id() + " must be skipped in safe mode");
            }
        }
    }

    @Test
    void environmentSafeModeAloneSkipsNonEssentialContributors() throws Exception {
        for (final HookContributor contributor : manifestContributors()) {
            final HookEnvironment environment =
                    HookEnvironment.builder().safeMode(true).build();
            if (contributor.requiredInSafeMode()) {
                assertNull(
                        TurboismAgent.startupPolicyRefusal(contributor, environment),
                        contributor.id() + " is required in safe mode");
            } else {
                assertNotNull(
                        TurboismAgent.startupPolicyRefusal(contributor, environment),
                        contributor.id() + " must be skipped in safe mode");
            }
        }
    }

    @Test
    void essentialContributorsStillHonourTheKillSwitch() throws Exception {
        for (final HookContributor contributor : manifestContributors()) {
            if (!contributor.requiredInSafeMode()) {
                continue;
            }
            final HookEnvironment environment = environment(policy(true, Set.of(contributor.policyId())));
            assertNotNull(
                    TurboismAgent.startupPolicyRefusal(contributor, environment),
                    contributor.id() + " must honour hooks.disabledIds even in safe mode");
        }
    }

    @Test
    void enabledPolicyPermitsEveryContributor() throws Exception {
        for (final HookContributor contributor : manifestContributors()) {
            assertNull(
                    TurboismAgent.startupPolicyRefusal(contributor, environment(policy(false, Set.of()))),
                    contributor.id() + " must not be refused by an enabled policy");
        }
    }
}
