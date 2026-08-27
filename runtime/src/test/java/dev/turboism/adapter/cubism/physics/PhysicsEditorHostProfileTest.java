package dev.turboism.adapter.cubism.physics;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicsEditorHostProfileTest {

    @Test
    void exact5303CarriesTheReviewedPhysicsTuple() {
        final PhysicsEditorHostProfile profile = PhysicsEditorHostProfile.forArtifact(
            ReviewedHostArtifacts.CUBISM_5_3_03
        ).orElseThrow();

        assertEquals(
            "com/live2d/cubism/doc/modeling/ui/viewer/physics/ViewerPhysics_GroupList$GroupListPanel",
            profile.panelOwnerInternalName()
        );
        assertEquals("getTableArea", profile.tableGetter());
        assertEquals("b", profile.checkpointMethod());
        assertEquals("n", profile.commitMethod());
        assertEquals("d", profile.rollbackMethod());
    }

    @Test
    void unknownArtifactsRemainRejected() {
        assertTrue(PhysicsEditorHostProfile.forArtifact(
            new HostArtifactDigest(1L, "0".repeat(64))
        ).isEmpty());
    }
}
