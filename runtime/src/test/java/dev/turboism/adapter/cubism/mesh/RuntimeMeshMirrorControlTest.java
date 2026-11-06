package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.mesh.MeshEditTool;
import dev.turboism.sdk.cubism.mesh.MeshMirrorControl;
import dev.turboism.sdk.cubism.mesh.MeshMirrorCounterpartResolver;
import dev.turboism.sdk.plugin.Registration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The consolidated control is a composition, not a copy: the session-shared half stays shared
 * and the resolver override stays per plugin.
 */
final class RuntimeMeshMirrorControlTest {

    @AfterEach
    void reset() {
        NativeMeshMirrorBridge.moveParticipation().resetSession();
        NativeMeshMirrorBridge.toolEligibility().resetSession();
        NativeMeshMirrorBridge.counterparts().resetSession();
        NativeMeshMirrorBridge.uninstall();
    }

    @Test
    void theAxisTheToolSetAndMovementAreSessionStateNotPerPlugin() {
        final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
        final MeshMirrorControl first = RuntimeMeshMirrorControl.forSession(axis);
        final MeshMirrorControl second = RuntimeMeshMirrorControl.forSession(axis);

        first.setCurrentAngleDegrees(30.0f);
        assertEquals(30.0f, axis.currentAngleDegrees());
        assertEquals(30.0f, second.currentAngleDegrees(), "the mirror axis belongs to the session");

        final Registration tools = first.extendEligibleTools(Set.of(MeshEditTool.LASSO));
        assertTrue(NativeMeshMirrorBridge.toolEligibility().isExtended(MeshEditTool.LASSO));
        tools.close();
        assertFalse(NativeMeshMirrorBridge.toolEligibility().isExtended(MeshEditTool.LASSO));

        final Registration moves = second.participate();
        assertTrue(NativeMeshMirrorBridge.moveParticipation().hasParticipants());
        moves.close();
        assertFalse(NativeMeshMirrorBridge.moveParticipation().hasParticipants());
    }

    @Test
    void eachPluginKeepsItsOwnResolverSlot() {
        final RuntimeMeshMirrorAxisService axis = new RuntimeMeshMirrorAxisService();
        final MeshMirrorControl first = RuntimeMeshMirrorControl.forSession(axis);
        final MeshMirrorControl second = RuntimeMeshMirrorControl.forSession(axis);
        final MeshMirrorCounterpartResolver firstResolver = (source, mesh, mirrorAxis) -> Optional.empty();
        final MeshMirrorCounterpartResolver secondResolver = (source, mesh, mirrorAxis) -> Optional.empty();

        final Registration firstSlot = first.overrideResolver(firstResolver);
        assertDoesNotThrow(
                () -> second.overrideResolver(secondResolver), "one plugin's override must not block another's");
        assertThrows(
                IllegalStateException.class,
                () -> first.overrideResolver(secondResolver),
                "a plugins second override is a programming error");

        firstSlot.close();
        assertDoesNotThrow(() -> first.overrideResolver(secondResolver), "closing the registration frees the slot");
    }
}
