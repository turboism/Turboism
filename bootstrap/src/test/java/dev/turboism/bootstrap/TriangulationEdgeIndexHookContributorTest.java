package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.config.RuntimeStartupConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TriangulationEdgeIndexHookContributorTest {

    @TempDir
    Path home;

    private static RuntimeStartupConfig policy(final boolean safeMode, final String... disabled) {
        return new RuntimeStartupConfig(
                safeMode,
                false,
                false,
                false,
                !safeMode && false,
                !safeMode && false,
                !safeMode && false,
                Set.of(disabled));
    }

    @Test
    void admittedOnlyWhenPolicyAllowsAndPreferenceEnabled() throws Exception {
        assertTrue(TriangulationEdgeIndexHookContributor.enabled(policy(false), home));
        Files.writeString(home.resolve("config.json"), "{\"meshTriangulationEdgeIndex\": false}");
        assertFalse(TriangulationEdgeIndexHookContributor.enabled(policy(false), home));
    }

    @Test
    void safeModeDisablesTheHook() {
        assertFalse(TriangulationEdgeIndexHookContributor.enabled(policy(true), home));
    }

    @Test
    void operatorDisableListDisablesTheHook() {
        assertFalse(TriangulationEdgeIndexHookContributor.enabled(
                policy(false, "cubism.mesh.triangulation-edge-index"), home));
    }

    @Test
    void absentPolicyOrNullHomeDisablesTheHook() {
        assertFalse(TriangulationEdgeIndexHookContributor.enabled(null, home));
    }

    @Test
    void lifecycleShapeIsPremainAndClosesOnExit() {
        final TriangulationEdgeIndexHookContributor contributor = new TriangulationEdgeIndexHookContributor();
        assertEquals(HookContributor.Phase.PREMAIN, contributor.phase());
        assertTrue(contributor.closesOnProcessExit());
        assertEquals("TURBOISM_TRIANGULATION_EDGE_INDEX", contributor.id());
    }
}
