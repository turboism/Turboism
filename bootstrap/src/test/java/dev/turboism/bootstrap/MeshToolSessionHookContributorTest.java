package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.adapter.cubism.startup.StartupSuppressionInstaller.AttachmentMode;
import org.junit.jupiter.api.Test;

class MeshToolSessionHookContributorTest {
    @Test
    void requiresPremainAndAnAdmittedEditorModelSlice() {
        final var contributor = new MeshToolSessionHookContributor();
        for (String version : java.util.List.of("5.2.03", "5.3.02", "5.3.03")) {
            assertTrue(contributor.admitted(environment(version, true, AttachmentMode.PREMAIN)));
            assertFalse(contributor.admitted(environment(version, false, AttachmentMode.PREMAIN)));
            assertFalse(contributor.admitted(environment(version, true, AttachmentMode.AGENTMAIN)));
        }
        assertFalse(contributor.admitted(environment("5.3.04", true, AttachmentMode.PREMAIN)));
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
