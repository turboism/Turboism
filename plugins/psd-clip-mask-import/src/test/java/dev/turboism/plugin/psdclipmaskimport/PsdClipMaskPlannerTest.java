package dev.turboism.plugin.psdclipmaskimport;

import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot;
import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot.PsdLayerSnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.IntSequence;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PsdClipMaskPlannerTest {

    private static final ArtMeshId TARGET_A = new ArtMeshId("ArtMeshA");
    private static final ArtMeshId TARGET_B = new ArtMeshId("ArtMeshB");
    private static final ArtMeshId MASK_A = new ArtMeshId("ArtMeshMaskA");
    private static final ArtMeshId MASK_B = new ArtMeshId("ArtMeshMaskB");
    private static final ArtMeshId MASK_C = new ArtMeshId("ArtMeshMaskC");
    private static final ArtMeshId MISSING = new ArtMeshId("NotInModel");

    @Test
    void mergesMultipleClippingLayersForOneTargetInSourceOrderWithDedupe() {
        final PsdClipMaskDocumentSnapshot psd = document("psd-face", List.of(
            layer("base-a", List.of(MASK_A), Optional.empty()),
            layer("clipped-a", List.of(TARGET_A), Optional.of("base-a")),
            layer("base-b", List.of(MASK_B, MASK_A), Optional.empty()),
            layer("clipped-b", List.of(TARGET_A), Optional.of("base-b"))
        ));

        final PsdClipMaskPlan plan = new PsdClipMaskPlanner().plan(
            List.of(psd),
            drawables(TARGET_A, MASK_A, MASK_B)
        );

        assertEquals(1, plan.assignments().size());
        final PsdClipMaskPlan.Assignment assignment = plan.assignments().get(0);
        assertEquals(TARGET_A, assignment.targetArtMeshId());
        assertEquals(List.of(MASK_A, MASK_B), assignment.orderedMaskArtMeshIds());
        assertEquals(List.of(
            new PsdClipMaskPlan.SourceRef("psd-face", "clipped-a"),
            new PsdClipMaskPlan.SourceRef("psd-face", "clipped-b")
        ), assignment.sourceLayers());
        assertTrue(plan.skips().isEmpty());
        assertTrue(plan.conflicts().isEmpty());
    }

    @Test
    void firstInvalidRelationshipDoesNotPoisonLaterValidOnes() {
        final PsdClipMaskDocumentSnapshot psd = document("psd-face", List.of(
            layer("clipped-bad", List.of(TARGET_A), Optional.of("absent-base")),
            layer("base-good", List.of(MASK_A), Optional.empty()),
            layer("clipped-good", List.of(TARGET_A), Optional.of("base-good"))
        ));

        final PsdClipMaskPlan plan = new PsdClipMaskPlanner().plan(
            List.of(psd),
            drawables(TARGET_A, MASK_A)
        );

        assertEquals(List.of(TARGET_A), plan.assignments().stream()
            .map(PsdClipMaskPlan.Assignment::targetArtMeshId).toList());
        assertEquals(List.of(MASK_A), plan.assignments().get(0).orderedMaskArtMeshIds());
        assertEquals(1, plan.skips().size());
        assertEquals(PsdClipMaskPlan.SkipReason.BASE_LAYER_UNRESOLVED, plan.skips().get(0).reason());
        assertEquals("clipped-bad", plan.skips().get(0).sourceLayers().get(0).layerId());
    }

    @Test
    void sameTargetAcrossDocumentsFailsClosedAsAmbiguous() {
        final PsdClipMaskDocumentSnapshot first = document("psd-first", List.of(
            layer("base-a", List.of(MASK_A), Optional.empty()),
            layer("clipped", List.of(TARGET_A), Optional.of("base-a"))
        ));
        final PsdClipMaskDocumentSnapshot second = document("psd-second", List.of(
            layer("base-b", List.of(MASK_B), Optional.empty()),
            layer("clipped", List.of(TARGET_A), Optional.of("base-b"))
        ));

        final PsdClipMaskPlan plan = new PsdClipMaskPlanner().plan(
            List.of(first, second),
            drawables(TARGET_A, MASK_A, MASK_B)
        );

        assertTrue(plan.assignments().isEmpty(), "ambiguous target must not be written");
        assertTrue(plan.conflicts().isEmpty());
        assertEquals(1, plan.skips().size());
        final PsdClipMaskPlan.Skip skip = plan.skips().get(0);
        assertEquals(TARGET_A, skip.targetArtMeshId());
        assertEquals(PsdClipMaskPlan.SkipReason.AMBIGUOUS_DOCUMENT, skip.reason());
        assertEquals(List.of(
            new PsdClipMaskPlan.SourceRef("psd-first", "clipped"),
            new PsdClipMaskPlan.SourceRef("psd-second", "clipped")
        ), skip.sourceLayers());
        assertTrue(skip.detail().contains("psd-first"));
        assertTrue(skip.detail().contains("psd-second"));
    }

    @Test
    void oneValidAndOneInvalidRelationshipAcrossDocumentsFailsClosedAsAmbiguous() {
        final PsdClipMaskDocumentSnapshot first = document("psd-first", List.of(
            layer("base-a", List.of(MASK_A), Optional.empty()),
            layer("clipped", List.of(TARGET_A), Optional.of("base-a"))
        ));
        final PsdClipMaskDocumentSnapshot second = document("psd-second", List.of(
            // invalid: clipping=true with an absent base layer
            layer("clipped-bad", List.of(TARGET_A), Optional.of("absent-base"))
        ));

        final PsdClipMaskPlan plan = new PsdClipMaskPlanner().plan(
            List.of(first, second),
            drawables(TARGET_A, MASK_A)
        );

        assertTrue(plan.assignments().isEmpty(), "ambiguous target must not be written");
        assertTrue(plan.conflicts().isEmpty());
        assertEquals(2, plan.skips().size());
        assertEquals(PsdClipMaskPlan.SkipReason.BASE_LAYER_UNRESOLVED, plan.skips().get(0).reason());
        final PsdClipMaskPlan.Skip ambiguity = plan.skips().get(1);
        assertEquals(PsdClipMaskPlan.SkipReason.AMBIGUOUS_DOCUMENT, ambiguity.reason());
        assertEquals(TARGET_A, ambiguity.targetArtMeshId());
        assertEquals(2, ambiguity.sourceLayers().size());
        assertTrue(ambiguity.detail().contains("psd-first"));
        assertTrue(ambiguity.detail().contains("psd-second"));
    }

    @Test
    void clippingWithoutAnyResolvableBaseIsAnExplicitSkip() {
        final PsdClipMaskDocumentSnapshot psd = document("psd-face", List.of(
            new PsdLayerSnapshot(
                "clipped-no-base", "Clipped", true, true,
                List.of(TARGET_A), Optional.empty(), List.of()
            )
        ));

        final PsdClipMaskPlan plan = new PsdClipMaskPlanner().plan(
            List.of(psd),
            drawables(TARGET_A)
        );

        assertTrue(plan.isEmpty());
        assertEquals(1, plan.skips().size());
        assertEquals(PsdClipMaskPlan.SkipReason.BASE_LAYER_UNRESOLVED, plan.skips().get(0).reason());
        assertEquals("clipped-no-base", plan.skips().get(0).sourceLayers().get(0).layerId());
    }

    @Test
    void emptyMasksWithInvertedStateRemainAnOverwriteConflict() {
        final PsdClipMaskDocumentSnapshot psd = document("psd-face", List.of(
            layer("mask-base", List.of(MASK_A), Optional.empty()),
            layer("clipped", List.of(TARGET_A), Optional.of("mask-base"))
        ));

        final PsdClipMaskPlan plan = new PsdClipMaskPlanner().plan(
            List.of(psd),
            drawablesWithMasks(List.of(TARGET_A, MASK_A), TARGET_A, List.of(), true)
        );

        assertTrue(plan.assignments().isEmpty());
        assertEquals(1, plan.conflicts().size());
        final PsdClipMaskPlan.Conflict conflict = plan.conflicts().get(0);
        assertTrue(conflict.existingInverted());
        assertEquals(List.of(), conflict.existingMaskArtMeshIds());
        assertEquals(List.of(MASK_A), conflict.plannedMaskArtMeshIds());
    }

    @Test
    void emptyOrSelfOnlyMasksAreExplicitNoResolvedMasksSkips() {
        final PsdClipMaskDocumentSnapshot psd = document("psd-face", List.of(
            layer("self-base", List.of(TARGET_A), Optional.empty()),
            layer("clipped-self", List.of(TARGET_A), Optional.of("self-base")),
            layer("empty-base", List.of(), Optional.empty()),
            layer("clipped-empty", List.of(TARGET_B), Optional.of("empty-base"))
        ));

        final PsdClipMaskPlan plan = new PsdClipMaskPlanner().plan(
            List.of(psd),
            drawables(TARGET_A, TARGET_B)
        );

        assertTrue(plan.assignments().isEmpty());
        assertTrue(plan.conflicts().isEmpty());
        assertEquals(2, plan.skips().size());
        assertEquals(PsdClipMaskPlan.SkipReason.NO_RESOLVED_MASKS, plan.skips().get(0).reason());
        assertEquals("clipped-self", plan.skips().get(0).sourceLayers().get(0).layerId());
        assertEquals(PsdClipMaskPlan.SkipReason.NO_RESOLVED_MASKS, plan.skips().get(1).reason());
        assertEquals("clipped-empty", plan.skips().get(1).sourceLayers().get(0).layerId());
    }

    @Test
    void alreadyMatchingOrderedMasksAreSkippedAsNoChange() {
        final PsdClipMaskDocumentSnapshot psd = document("psd-face", List.of(
            layer("mask-base", List.of(MASK_A), Optional.empty()),
            layer("clipped", List.of(TARGET_A), Optional.of("mask-base"))
        ));

        final PsdClipMaskPlan plan = new PsdClipMaskPlanner().plan(
            List.of(psd),
            drawablesWithMasks(List.of(TARGET_A, MASK_A), TARGET_A, List.of(MASK_A), false)
        );

        assertTrue(plan.assignments().isEmpty());
        assertTrue(plan.conflicts().isEmpty());
        assertEquals(1, plan.skips().size());
        assertEquals(PsdClipMaskPlan.SkipReason.ALREADY_MATCHES, plan.skips().get(0).reason());
    }

    @Test
    void matchingMasksWithInvertedStateRemainAnOverwriteConflict() {
        final PsdClipMaskDocumentSnapshot psd = document("psd-face", List.of(
            layer("mask-base", List.of(MASK_A), Optional.empty()),
            layer("clipped", List.of(TARGET_A), Optional.of("mask-base"))
        ));

        final PsdClipMaskPlan plan = new PsdClipMaskPlanner().plan(
            List.of(psd),
            drawablesWithMasks(List.of(TARGET_A, MASK_A), TARGET_A, List.of(MASK_A), true)
        );

        assertTrue(plan.assignments().isEmpty());
        assertEquals(1, plan.conflicts().size());
        assertEquals(TARGET_A, plan.conflicts().get(0).targetArtMeshId());
        assertTrue(plan.conflicts().get(0).existingInverted());
    }

    @Test
    void missingTargetIsSkippedWithExplicitReason() {
        final PsdClipMaskDocumentSnapshot psd = document("psd-face", List.of(
            layer("mask-base", List.of(MASK_A), Optional.empty()),
            layer("clipped-missing", List.of(MISSING), Optional.of("mask-base"))
        ));

        final PsdClipMaskPlan plan = new PsdClipMaskPlanner().plan(
            List.of(psd),
            drawables(MASK_A)
        );

        assertTrue(plan.isEmpty());
        assertEquals(1, plan.skips().size());
        assertEquals(PsdClipMaskPlan.SkipReason.TARGET_UNRESOLVED, plan.skips().get(0).reason());
        assertEquals(MISSING, plan.skips().get(0).targetArtMeshId());
        assertEquals("clipped-missing", plan.skips().get(0).sourceLayers().get(0).layerId());
    }

    @Test
    void missingMaskIsSkippedWithExplicitReasonAndNeverWritten() {
        final PsdClipMaskDocumentSnapshot psd = document("psd-face", List.of(
            layer("mask-base", List.of(MASK_A, MISSING), Optional.empty()),
            layer("clipped", List.of(TARGET_A), Optional.of("mask-base"))
        ));

        final PsdClipMaskPlan plan = new PsdClipMaskPlanner().plan(
            List.of(psd),
            drawables(TARGET_A, MASK_A)
        );

        assertTrue(plan.isEmpty());
        assertEquals(1, plan.skips().size());
        assertEquals(PsdClipMaskPlan.SkipReason.MASK_UNRESOLVED, plan.skips().get(0).reason());
        assertTrue(plan.skips().get(0).detail().contains(MISSING.value()));
    }

    @Test
    void unresolvedBaseLayerIsSkippedWithExplicitReason() {
        final PsdClipMaskDocumentSnapshot psd = document("psd-face", List.of(
            layer("clipped", List.of(TARGET_A), Optional.of("absent-base"))
        ));

        final PsdClipMaskPlan plan = new PsdClipMaskPlanner().plan(
            List.of(psd),
            drawables(TARGET_A)
        );

        assertTrue(plan.isEmpty());
        assertEquals(PsdClipMaskPlan.SkipReason.BASE_LAYER_UNRESOLVED, plan.skips().get(0).reason());
    }

    @Test
    void existingNonEmptyMaskIsAnOverwriteConflictNotAnAssignment() {
        final PsdClipMaskDocumentSnapshot psd = document("psd-face", List.of(
            layer("mask-base", List.of(MASK_A), Optional.empty()),
            layer("clipped", List.of(TARGET_A), Optional.of("mask-base"))
        ));

        final PsdClipMaskPlan plan = new PsdClipMaskPlanner().plan(
            List.of(psd),
            drawablesWithMasks(List.of(TARGET_A, MASK_A), TARGET_A, List.of(MASK_B), false)
        );

        assertTrue(plan.assignments().isEmpty());
        assertEquals(1, plan.conflicts().size());
        final PsdClipMaskPlan.Conflict conflict = plan.conflicts().get(0);
        assertEquals(TARGET_A, conflict.targetArtMeshId());
        assertEquals(List.of(MASK_B), conflict.existingMaskArtMeshIds());
        assertFalse(conflict.existingInverted());
        assertEquals(List.of(MASK_A), conflict.plannedMaskArtMeshIds());
        assertEquals("clipped", conflict.sourceLayers().get(0).layerId());
    }

    @Test
    void duplicateDocumentIdsFailClosedInsteadOfMerging() {
        final PsdClipMaskDocumentSnapshot first = document("psd-face", List.of(
            layer("base-a", List.of(MASK_A), Optional.empty()),
            layer("clipped-a", List.of(TARGET_A), Optional.of("base-a"))
        ));
        final PsdClipMaskDocumentSnapshot second = document("psd-face", List.of(
            layer("base-b", List.of(MASK_B), Optional.empty()),
            layer("clipped-b", List.of(TARGET_B), Optional.of("base-b"))
        ));

        final IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new PsdClipMaskPlanner().plan(
                List.of(first, second),
                drawables(TARGET_A, TARGET_B, MASK_A, MASK_B)
            )
        );

        org.junit.jupiter.api.Assertions.assertTrue(
            error.getMessage().contains("psd-face"), error.getMessage()
        );
    }

    @Test
    void emptyCandidatesProduceAnEmptyPlan() {
        final PsdClipMaskDocumentSnapshot psd = document("psd-face", List.of(
            layer("plain-layer", List.of(MASK_A), Optional.empty())
        ));

        final PsdClipMaskPlan plan = new PsdClipMaskPlanner().plan(
            List.of(psd),
            drawables(TARGET_A, MASK_A)
        );

        assertTrue(plan.isEmpty());
        assertTrue(plan.assignments().isEmpty());
        assertTrue(plan.conflicts().isEmpty());
        assertTrue(plan.skips().isEmpty());
    }

    @Test
    void oneLayerBindingMultipleArtMeshesPlansOneItemPerTarget() {
        final PsdClipMaskDocumentSnapshot psd = document("psd-face", List.of(
            layer("mask-base", List.of(MASK_A), Optional.empty()),
            layer("clipped", List.of(TARGET_A, TARGET_B), Optional.of("mask-base"))
        ));

        final PsdClipMaskPlan plan = new PsdClipMaskPlanner().plan(
            List.of(psd),
            drawables(TARGET_A, TARGET_B, MASK_A)
        );

        assertEquals(List.of(TARGET_A, TARGET_B), plan.assignments().stream()
            .map(PsdClipMaskPlan.Assignment::targetArtMeshId).toList());
        assertEquals(List.of(MASK_A), plan.assignments().get(0).orderedMaskArtMeshIds());
        assertEquals(List.of(MASK_A), plan.assignments().get(1).orderedMaskArtMeshIds());
    }

    @Test
    void collectsBaseSubtreeRecursivelyInSourceOrder() {
        final PsdClipMaskDocumentSnapshot psd = document("psd-face", List.of(
            new PsdLayerSnapshot(
                "mask-group",
                "Mask Group",
                true,
                List.of(MASK_A),
                Optional.empty(),
                List.of(
                    layer("mask-child", List.of(MASK_B, MASK_C), Optional.empty()),
                    layer("mask-grandchild", List.of(MASK_A), Optional.empty())
                )
            ),
            layer("clipped", List.of(TARGET_A), Optional.of("mask-group"))
        ));

        final PsdClipMaskPlan plan = new PsdClipMaskPlanner().plan(
            List.of(psd),
            drawables(TARGET_A, MASK_A, MASK_B, MASK_C)
        );

        assertEquals(List.of(MASK_A, MASK_B, MASK_C),
            plan.assignments().get(0).orderedMaskArtMeshIds());
    }

    private static PsdClipMaskDocumentSnapshot document(final String id, final List<PsdLayerSnapshot> layers) {
        return new PsdClipMaskDocumentSnapshot(id, "textures/" + id + ".psd", layers);
    }

    private static PsdLayerSnapshot layer(
        final String layerId,
        final List<ArtMeshId> artMeshIds,
        final Optional<String> clippingBaseLayerId
    ) {
        return new PsdLayerSnapshot(
            layerId,
            layerId,
            true,
            artMeshIds,
            clippingBaseLayerId,
            List.of()
        );
    }

    private static List<Drawable> drawables(final ArtMeshId... ids) {
        return drawablesWithMasks(List.of(ids), null, List.of(), false);
    }

    private static List<Drawable> drawablesWithMasks(
        final List<ArtMeshId> ids,
        final ArtMeshId maskedTarget,
        final List<ArtMeshId> masks,
        final boolean inverted
    ) {
        final List<Drawable> result = new ArrayList<>();
        for (ArtMeshId id : ids) {
            result.add(new Drawable() {
                @Override public ArtMeshId id() { return id; }
                @Override public byte constantFlag() { return 0; }
                @Override public byte dynamicFlag() { return 0; }
                @Override public BlendMode blendMode() { return BlendMode.NORMAL; }
                @Override public int textureIndex() { return 0; }
                @Override public int drawOrder() { return 0; }
                @Override public int renderOrder() { return 0; }
                @Override public float getOpacity() { return 1.0F; }
                @Override public IntSequence masks() { return emptyInts(); }
                @Override public FloatSequence vertexPositions() { return emptyFloats(); }
                @Override public FloatSequence vertexUvs() { return emptyFloats(); }
                @Override public IntSequence indices() { return emptyInts(); }
                @Override public Color multiplyColor() { return new Color(1, 1, 1, 1); }
                @Override public Color screenColor() { return new Color(0, 0, 0, 1); }
                @Override public int parentPartIndex() { return -1; }
                @Override public int parentDeformerIndex() { return -1; }
                @Override public IntSequence parameters() { return emptyInts(); }
                @Override public List<ArtMeshId> maskIds() {
                    return id.equals(maskedTarget) ? masks : List.of();
                }
                @Override public boolean invertedMask() { return inverted; }
            });
        }
        return result;
    }

    private static IntSequence emptyInts() {
        return new IntSequence() {
            @Override public int size() { return 0; }
            @Override public int get(final int index) { throw new IndexOutOfBoundsException(index); }
        };
    }

    private static FloatSequence emptyFloats() {
        return new FloatSequence() {
            @Override public int size() { return 0; }
            @Override public float get(final int index) { throw new IndexOutOfBoundsException(index); }
        };
    }
}
