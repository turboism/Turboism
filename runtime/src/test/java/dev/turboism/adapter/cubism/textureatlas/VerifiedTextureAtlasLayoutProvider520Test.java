package dev.turboism.adapter.cubism.textureatlas;

import static dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasFixture.Atlas;
import static dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasFixture.Fixture;
import static dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasFixture.PROFILE_5_2_03;
import static dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasFixture.PROFILE_5_3_02;
import static dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasFixture.captured;
import static dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasFixture.provider;
import static dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasFixture.resolver;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement;
import java.awt.geom.AffineTransform;
import java.util.List;
import org.junit.jupiter.api.Test;

class VerifiedTextureAtlasLayoutProvider520Test {

    @Test
    void projectsCurrentAtlasAndAppliesACompletePlanThroughNativeAtlasTransaction() {
        final Fixture fixture = new Fixture(false);
        final VerifiedTextureAtlasLayoutProvider provider = provider(resolver("5.2.03", true), "session-a", fixture);

        final TextureAtlasAuthoringState current = provider.current().orElseThrow();
        assertEquals("session-a", current.documentId());
        assertEquals("model-a", current.modelId());
        assertEquals("atlas-a", current.atlasId());
        assertEquals(2, current.items().size());
        assertEquals(8, current.currentPlan().placements().get(1).x());

        final TextureAtlasLayoutPlan plan = new TextureAtlasLayoutPlan(
                16,
                8,
                2,
                List.of(
                        new TextureAtlasPlacement("texture-a", 0, 2, 1, 4, 3, false),
                        new TextureAtlasPlacement("texture-b", 1, 5, 2, 2, 2, false)));
        assertEquals(TextureAtlasLayoutProvider.ApplyOutcome.APPLIED, provider.apply(current, plan));

        assertEquals(
                List.of("Turboism Atlas 1", "Turboism Atlas 2"),
                fixture.source.textureManager.atlases.stream().map(Atlas::name).toList());
        assertEquals(
                List.of("texture-a"),
                fixture.source.textureManager.atlases.get(0).entries.stream()
                        .map(entry -> entry.image.guid.value)
                        .toList());
        assertEquals(
                List.of("texture-b"),
                fixture.source.textureManager.atlases.get(1).entries.stream()
                        .map(entry -> entry.image.guid.value)
                        .toList());
        assertEquals(
                AffineTransform.getTranslateInstance(2.0, 1.0),
                fixture.source.textureManager.atlases.get(0).entries.get(0).transform);
        assertEquals(
                AffineTransform.getTranslateInstance(5.0, 2.0),
                fixture.source.textureManager.atlases.get(1).entries.get(0).transform);
        assertEquals(
                List.of("atlas-a"),
                fixture.data.atlases.stream().map(Atlas::name).toList());
        assertEquals(1, fixture.data.applyCount);
        assertEquals(List.of("Update TextureAtlas"), fixture.document.editMode.edits);
        assertEquals(1, fixture.document.editMode.undos.size());
        assertEquals(List.of(false), fixture.document.editMode.rollbacks);
        assertEquals(1, fixture.source.semanticRelinkCount);
        assertEquals(1, fixture.source.verifyCount);
        assertEquals(1, fixture.source.textureManager.atlasInputChangeCount);
        assertEquals(1, fixture.source.refreshCount);
        assertEquals(1, fixture.document.repaintCount);
        assertEquals(1, fixture.document.dirtyCount);

        fixture.document.editMode.undo();
        assertEquals(
                List.of("atlas-a"),
                fixture.source.textureManager.atlases.stream().map(Atlas::name).toList());
        assertEquals(2, fixture.source.semanticRelinkCount);
        assertEquals(2, fixture.source.verifyCount);
        assertEquals(2, fixture.source.textureManager.atlasInputChangeCount);
        assertEquals(2, fixture.source.refreshCount);
        assertEquals(2, fixture.document.repaintCount);
        fixture.document.editMode.redo();
        assertEquals(
                List.of("Turboism Atlas 1", "Turboism Atlas 2"),
                fixture.source.textureManager.atlases.stream().map(Atlas::name).toList());
        assertEquals(3, fixture.source.semanticRelinkCount);
        assertEquals(3, fixture.source.verifyCount);
        assertEquals(3, fixture.source.textureManager.atlasInputChangeCount);
        assertEquals(3, fixture.source.refreshCount);
        assertEquals(3, fixture.document.repaintCount);

        final TextureAtlasAuthoringState updated = provider.current().orElseThrow();
        assertEquals(current.revision() + 1, updated.revision());
        assertEquals(plan, updated.currentPlan());
    }

    @Test
    void rejectsStalePlanningStateAndUnsupportedIdentityBeforeMutation() {
        final Fixture fixture = new Fixture(false);
        final VerifiedTextureAtlasLayoutProvider provider = provider(resolver("5.2.03", true), "session-a", fixture);
        final TextureAtlasAuthoringState stale = provider.current().orElseThrow();
        fixture.replaceAtlasWithSameId();

        assertEquals(TextureAtlasLayoutProvider.ApplyOutcome.REJECTED, provider.apply(stale, stale.currentPlan()));
        assertEquals(0, fixture.data.applyCount);
        assertFalse(provider(resolver("5.3.02", true), "session-a", fixture)
                .current()
                .isPresent());
        assertFalse(new VerifiedTextureAtlasLayoutProvider(
                        resolver("5.2.03", true), "session-a", captured(fixture), PROFILE_5_3_02)
                .current()
                .isPresent());
        assertFalse(provider(resolver("5.2.03", false), "session-a", fixture)
                .current()
                .isPresent());
    }

    @Test
    void stagedConstructionFailureLeavesEditorStateUntouched() {
        final Fixture fixture = new Fixture(false);
        fixture.data.failAtlasName = "Turboism Atlas 2";
        final VerifiedTextureAtlasLayoutProvider provider = provider(resolver("5.2.03", true), "session-a", fixture);
        final TextureAtlasAuthoringState current = provider.current().orElseThrow();
        final TextureAtlasLayoutPlan plan = new TextureAtlasLayoutPlan(
                16,
                8,
                2,
                List.of(
                        new TextureAtlasPlacement("texture-a", 0, 2, 1, 4, 3, false),
                        new TextureAtlasPlacement("texture-b", 1, 5, 2, 2, 2, false)));

        assertThrows(RuntimeException.class, () -> provider.apply(current, plan));
        assertEquals(
                List.of("atlas-a"),
                fixture.source.textureManager.atlases.stream().map(Atlas::name).toList());
        assertEquals(0, fixture.data.applyCount);
        assertTrue(fixture.document.editMode.edits.isEmpty());
        assertTrue(fixture.document.editMode.undos.isEmpty());
        assertTrue(fixture.document.editMode.rollbacks.isEmpty());
    }

    private static VerifiedTextureAtlasLayoutProvider provider(
            final VerifiedMemberResolver resolver, final String sessionIdentity, final Fixture fixture) {
        return VerifiedTextureAtlasFixture.provider(resolver, sessionIdentity, fixture, PROFILE_5_2_03);
    }
}
