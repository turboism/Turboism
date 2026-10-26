package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.turboism.config.RuntimeStartupConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TurboismAgentAtlasTileBboxPolicyTest {
    private static final String HOOK_ID = "cubism.textureatlas.tile-bbox";

    @TempDir
    Path home;

    @Test
    void disabledExactPolicyPreventsEarlyInstallation() {
        final RuntimeStartupConfig policy =
                new RuntimeStartupConfig(false, false, false, false, false, false, false, Set.of(HOOK_ID));
        assertFalse(AtlasTileBboxHookContributor.enabled(policy, home));
    }

    @Test
    void safeModePreventsEarlyInstallation() {
        final RuntimeStartupConfig policy =
                new RuntimeStartupConfig(true, false, false, false, false, false, false, Set.of());
        assertFalse(AtlasTileBboxHookContributor.enabled(policy, home));
    }

    @Test
    void nullPolicyRemainsFailClosed() {
        assertFalse(AtlasTileBboxHookContributor.enabled(null, home));
    }

    @Test
    void enabledPolicyStillRequiresExplicitOptIn() {
        final RuntimeStartupConfig policy =
                new RuntimeStartupConfig(false, false, false, false, false, false, false, Set.of());
        assertFalse(AtlasTileBboxHookContributor.enabled(policy, home), "rendering optimizations default off");
    }

    @Test
    void persistedFalseDisablesEvenUnderAnEnabledPolicy() throws Exception {
        Files.writeString(home.resolve("config.json"), """
            {"format": "turboism.runtime.config", "schemaVersion": 1,
             "worktreeId": "policy-test", "atlasTileBbox": false}
            """);
        final RuntimeStartupConfig policy =
                new RuntimeStartupConfig(false, false, false, false, false, false, false, Set.of());
        assertFalse(AtlasTileBboxHookContributor.enabled(policy, home));
    }
}
