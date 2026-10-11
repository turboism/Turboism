package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.adapter.cubism.startup.StartupSuppressionInstaller.AttachmentMode;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;

class MeshToolSessionHookContributorTest {
    @Test
    void requiresPremainAndAnAdmittedEditorModelSlice() {
        final var contributor = new MeshToolSessionHookContributor();
        for (String version : ReviewedHostArtifacts.reviewedCubismVersions()) {
            assertTrue(contributor.admitted(environment(version, true, AttachmentMode.PREMAIN)));
            assertFalse(contributor.admitted(environment(version, false, AttachmentMode.PREMAIN)));
            assertFalse(contributor.admitted(environment(version, true, AttachmentMode.AGENTMAIN)));
        }
        // An unreviewed declared version is never admitted.
        assertFalse(contributor.admitted(environment("5.4.00", true, AttachmentMode.PREMAIN)));
        assertTrue(contributor.closesOnProcessExit());
        assertEquals(java.util.Set.of("mesh-tool-session"), contributor.runtimeHookIds());
    }

    private static HookEnvironment environment(String version, boolean admitted, AttachmentMode mode) {
        return HookEnvironment.builder()
                .profile(version)
                .fullRuntimeAdmission(admitted)
                .attachmentMode(mode)
                .build();
    }
}
