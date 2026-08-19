package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectContextMenuHostProfileTest {

    @Test
    void pinsIndependent52And53OwnersAndAppendCardinality() {
        final ObjectContextMenuHostProfile v52 = ObjectContextMenuHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_2_03).orElseThrow();
        final ObjectContextMenuHostProfile v53 = ObjectContextMenuHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_3_02).orElseThrow();

        assertProfile(v52, "com/live2d/cubism/view/palette/parts/R", 21, 23, List.of(3, 3, 2, 1));
        assertProfile(v53, "com/live2d/cubism/view/palette/parts/T", 22, 22, List.of(1, 3, 1, 1));
    }

    @Test
    void rejectsUnknownArtifacts() {
        assertTrue(ObjectContextMenuHostProfile.forArtifact(
            new HostArtifactDigest(1L, "0".repeat(64))
        ).isEmpty());
    }

    private static void assertProfile(
        final ObjectContextMenuHostProfile profile,
        final String partsOwner,
        final int partsAppends,
        final int workspaceAppends,
        final List<Integer> injectionPoints
    ) {
        final List<VerifiedObjectContextMenuHookInstaller.Binding> bindings = profile.bindings();
        assertEquals(List.of(
            Location.DEFORMER_TAB,
            Location.PARAMETER_TAB,
            Location.PART_TAB,
            Location.WORKSPACE_OBJECT
        ), bindings.stream().map(VerifiedObjectContextMenuHookInstaller.Binding::location).toList());
        assertEquals(11, bindings.get(0).expectedAppendPoints());
        assertEquals(7, bindings.get(1).expectedAppendPoints());
        assertEquals("c", bindings.get(1).append().memberName());
        assertEquals(partsOwner, bindings.get(2).operation().ownerInternalName());
        assertEquals(partsAppends, bindings.get(2).expectedAppendPoints());
        assertEquals(workspaceAppends, bindings.get(3).expectedAppendPoints());
        assertEquals(injectionPoints,
            bindings.stream().map(VerifiedObjectContextMenuHookInstaller.Binding::injectionPoint).toList());
        assertEquals(List.of(List.of(2, 3), List.of(2), List.of(5), List.of(1)),
            bindings.stream().map(binding -> java.util.Arrays.stream(binding.sourceLocals()).boxed().toList()).toList());
    }
}
