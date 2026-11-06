package dev.turboism.adapter.cubism.textureatlas;

import static dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasFixture.PROFILE_5_3_02;
import static dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasFixture.provider;
import static dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasFixture.resolver;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.textureatlas.TextureAtlasLayoutProvider.ApplyOutcome;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPageSize;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The texture-atlas layout apply opens its own {@code edit-mode} group, so it must refuse while an
 * authoring transaction ambient scope is open on the host thread instead of creating a detached
 * Undo group the root transaction can neither join nor roll back.
 */
class VerifiedTextureAtlasLayoutProviderAmbientTransactionTest {

    @Test
    void refusesToApplyWhileAnAmbientAuthoringTransactionIsOpen() {
        final var fixture = new VerifiedTextureAtlasFixture.Fixture(false);
        final var provider = provider(resolver("5.3.02", true), "session", fixture, PROFILE_5_3_02, () -> true);
        final var before = provider.current().orElseThrow();
        final var atlas = fixture.source.textureManager.atlases.get(0);

        assertEquals(
                ApplyOutcome.REJECTED,
                provider.apply(before, plan()),
                "The apply must refuse while the ambient scope is open.");

        assertTrue(
                fixture.document.editMode.edits.isEmpty(),
                "The refusal must happen before the edit-mode group is opened.");
        assertTrue(fixture.document.editMode.undos.isEmpty(), "No atlas Undo may be staged.");
        assertTrue(fixture.document.editMode.rollbacks.isEmpty(), "No edit-mode end may run.");
        assertEquals(0, fixture.document.dirtyCount);
        assertEquals(0, fixture.document.repaintCount);
        assertEquals(0, fixture.source.semanticRelinkCount);
        assertEquals(0, fixture.source.verifyCount);
        assertEquals(0, fixture.source.refreshCount);
        assertEquals(0, fixture.data.applyCount);
        assertSame(atlas, fixture.source.textureManager.atlases.get(0), "The original atlas must stay in place.");
        assertEquals(before, provider.current().orElseThrow(), "The observed state must be untouched.");
    }

    @Test
    void appliesOutsideAnAmbientTransactionExactlyAsBefore() {
        final var fixture = new VerifiedTextureAtlasFixture.Fixture(false);
        final var provider = provider(resolver("5.3.02", true), "session", fixture, PROFILE_5_3_02, () -> false);
        final var before = provider.current().orElseThrow();

        assertEquals(ApplyOutcome.APPLIED, provider.apply(before, plan()));
        assertEquals(List.of("Update TextureAtlas"), fixture.document.editMode.edits);
        assertEquals(1, fixture.document.editMode.undos.size());
        assertEquals(List.of(false), fixture.document.editMode.rollbacks);
        assertEquals(1, fixture.document.dirtyCount);
        assertEquals(
                plan().placements(),
                provider.current().orElseThrow().currentPlan().placements());
    }

    private static TextureAtlasLayoutPlan plan() {
        return new TextureAtlasLayoutPlan(
                16,
                8,
                1,
                List.of("001 PSD"),
                List.of(
                        new TextureAtlasPlacement("texture-a", 0, 1, 1, 4, 3, false),
                        new TextureAtlasPlacement("texture-b", 0, 8, 1, 2, 2, false)),
                1D,
                List.of(new TextureAtlasPageSize(16, 8)));
    }
}
