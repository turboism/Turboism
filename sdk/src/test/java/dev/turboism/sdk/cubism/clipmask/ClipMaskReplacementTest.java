package dev.turboism.sdk.cubism.clipmask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClipMaskReplacementTest {

    @Test
    void preservesOrderedOwnedIdsAndRejectsInvalidExpectedState() {
        final ArrayList<ArtMeshId> expected = new ArrayList<>(List.of(
            new ArtMeshId("MaskB"), new ArtMeshId("MaskA")
        ));
        final ArrayList<ArtMeshId> replacement = new ArrayList<>(List.of(
            new ArtMeshId("MaskC"), new ArtMeshId("MaskB")
        ));
        final ClipMaskReplacement replacementValue = new ClipMaskReplacement(
            new ArtMeshId("Target"), expected, true, replacement, false
        );
        expected.clear();
        replacement.clear();

        assertEquals(List.of(new ArtMeshId("MaskB"), new ArtMeshId("MaskA")),
            replacementValue.expectedMaskArtMeshIds());
        assertEquals(List.of(new ArtMeshId("MaskC"), new ArtMeshId("MaskB")),
            replacementValue.replacementMaskArtMeshIds());
        assertEquals(true, replacementValue.expectedInverted());
        assertEquals(false, replacementValue.replacementInverted());
        assertThrows(UnsupportedOperationException.class,
            () -> replacementValue.expectedMaskArtMeshIds().add(new ArtMeshId("Late")));
        assertThrows(UnsupportedOperationException.class,
            () -> replacementValue.replacementMaskArtMeshIds().add(new ArtMeshId("Late")));
    }

    @Test
    void emptyExpectedStateIsAllowedButReplacementMustBeNonEmpty() {
        final ClipMaskReplacement clearThenReplace = new ClipMaskReplacement(
            new ArtMeshId("Target"), List.of(), false, List.of(new ArtMeshId("Mask")), false
        );
        assertEquals(List.of(), clearThenReplace.expectedMaskArtMeshIds());

        assertThrows(IllegalArgumentException.class,
            () -> new ClipMaskReplacement(
                new ArtMeshId("Target"), List.of(new ArtMeshId("Mask")), false, List.of(), false
            ));
    }

    @Test
    void rejectsNullTargetNullListsNullElementsDuplicatesAndSelfReference() {
        assertThrows(NullPointerException.class,
            () -> new ClipMaskReplacement(
                null, List.of(), false, List.of(new ArtMeshId("Mask")), false
            ));
        assertThrows(NullPointerException.class,
            () -> new ClipMaskReplacement(
                new ArtMeshId("Target"), null, false, List.of(new ArtMeshId("Mask")), false
            ));
        assertThrows(NullPointerException.class,
            () -> new ClipMaskReplacement(
                new ArtMeshId("Target"), List.of(), false, null, false
            ));
        assertThrows(NullPointerException.class,
            () -> new ClipMaskReplacement(
                new ArtMeshId("Target"),
                List.of(new ArtMeshId[] {null}),
                false,
                List.of(new ArtMeshId("Mask")),
                false
            ));
        assertThrows(NullPointerException.class,
            () -> new ClipMaskReplacement(
                new ArtMeshId("Target"),
                List.of(),
                false,
                List.of(new ArtMeshId[] {null}),
                false
            ));
        assertThrows(IllegalArgumentException.class,
            () -> new ClipMaskReplacement(
                new ArtMeshId("Target"), List.of(new ArtMeshId("Target")), false,
                List.of(new ArtMeshId("Mask")), false
            ));
        assertThrows(IllegalArgumentException.class,
            () -> new ClipMaskReplacement(
                new ArtMeshId("Target"), List.of(), false,
                List.of(new ArtMeshId("Target")), false
            ));
        assertThrows(IllegalArgumentException.class,
            () -> new ClipMaskReplacement(
                new ArtMeshId("Target"),
                List.of(new ArtMeshId("Mask"), new ArtMeshId("Mask")),
                false,
                List.of(new ArtMeshId("Other")),
                false
            ));
        assertThrows(IllegalArgumentException.class,
            () -> new ClipMaskReplacement(
                new ArtMeshId("Target"),
                List.of(),
                false,
                List.of(new ArtMeshId("Mask"), new ArtMeshId("Mask")),
                false
            ));
    }
}
