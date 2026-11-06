package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.mesh.MeshEditContribution;
import dev.turboism.sdk.cubism.mesh.MeshEditContributionSurface;
import dev.turboism.sdk.cubism.mesh.MeshEditUiService;
import dev.turboism.sdk.plugin.Registration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The consolidated contribution surface hands both verbs to the session-shared registries, and
 * the control definition crosses to the native control owner unchanged.
 */
final class RuntimeMeshEditContributionSurfaceTest {

    @AfterEach
    void reset() {
        NativeMeshMirrorBridge.participation().resetSession();
        NativeMeshMirrorBridge.uninstall();
    }

    @Test
    void participationRegistersOnTheSessionSharedRegistry() {
        final RuntimeMeshEditParticipation participation = new RuntimeMeshEditParticipation();
        final MeshEditContributionSurface surface =
                new RuntimeMeshEditContributionSurface(participation, new RuntimeMeshEditUiService());

        final Registration registration = surface.participate(deletion -> MeshEditContribution.none());

        assertTrue(participation.hasParticipants());
        registration.close();
        assertFalse(participation.hasParticipants());
    }

    @Test
    @SuppressWarnings("removal") // the native control owner still speaks the released record
    void theConsolidatedControlDefinitionReachesTheNativeControlOwner() {
        final RuntimeMeshEditUiService ui = new RuntimeMeshEditUiService();
        final MeshEditContributionSurface surface =
                new RuntimeMeshEditContributionSurface(new RuntimeMeshEditParticipation(), ui);
        final AtomicReference<Float> reported = new AtomicReference<>();

        final Registration registration =
                surface.contributeMirrorAxisAngleControl(new MeshEditContributionSurface.MirrorAxisAngleControl(
                        "mesh.mirror-axis.angle", "Label", "Reset", -180.0f, 180.0f, 0.5f, reported::set));

        final MeshEditUiService.MirrorAxisAngleControl contributed = ui.contribution();
        assertNotNull(contributed);
        assertEquals("mesh.mirror-axis.angle", contributed.contributionId());
        assertEquals("Label", contributed.label());
        assertEquals("Reset", contributed.resetToolTip());
        assertEquals(-180.0f, contributed.minimumDegrees());
        assertEquals(180.0f, contributed.maximumDegrees());
        assertEquals(0.5f, contributed.stepDegrees());

        contributed.onAngleChanged().accept(12.5f);
        assertEquals(12.5f, reported.get(), "the plugin callback must survive the conversion");

        registration.close();
        assertNull(ui.contribution());
    }
}
