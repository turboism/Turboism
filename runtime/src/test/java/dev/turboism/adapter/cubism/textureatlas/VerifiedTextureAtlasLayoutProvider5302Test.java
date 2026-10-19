package dev.turboism.adapter.cubism.textureatlas;

import static dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasFixture.*;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VerifiedTextureAtlasLayoutProvider5302Test {

    private static final VerifiedTextureAtlasSelectorContract.Profile PROFILE_5_2_03 =
            VerifiedTextureAtlasSelectorContract.profileFor("5.2.03").orElseThrow();
    private static final VerifiedTextureAtlasSelectorContract.Profile PROFILE_5_3_02 =
            VerifiedTextureAtlasSelectorContract.profileFor("5.3.02").orElseThrow();

    @Test
    void packsActiveModelWithoutOpeningAtlasEditorOnAdmittedVersions() {
        for (String version : List.of("5.2.03", "5.3.02", "5.3.03", "5.3.04")) {
            final Fixture fixture = new Fixture();
            final var capture = new TextureAtlasDataModelCapture();
            final var profile =
                    VerifiedTextureAtlasSelectorContract.profileFor(version).orElseThrow();
            final var provider = new VerifiedTextureAtlasLayoutProvider(
                    resolver(version, true), "session-" + version, capture, profile);
            final var before = provider.current().orElseThrow();
            final var plan = new TextureAtlasLayoutPlan(
                    16,
                    8,
                    1,
                    List.of(
                            new TextureAtlasPlacement("texture-a", 0, 2, 1, 4, 3, false),
                            new TextureAtlasPlacement("texture-b", 0, 8, 1, 2, 2, false),
                            new TextureAtlasPlacement("texture-unused", 0, 12, 1, 1, 1, false)));
            assertEquals(TextureAtlasLayoutProvider.ApplyOutcome.APPLIED, provider.apply(before, plan), version);
            assertEquals(plan, provider.current().orElseThrow().currentPlan(), version);
            assertEquals(1, fixture.document.dirtyCount, version);
        }
    }

    @Test
    void packsActiveModelWithoutOpeningAtlasEditor() {
        final Fixture fixture = new Fixture();
        final var capture = new TextureAtlasDataModelCapture();
        final var provider =
                new VerifiedTextureAtlasLayoutProvider(resolver("5.3.02", true), "session-a", capture, PROFILE_5_3_02);
        final var before = provider.current().orElseThrow();
        final var plan = new TextureAtlasLayoutPlan(
                16,
                8,
                1,
                List.of(
                        new TextureAtlasPlacement("texture-a", 0, 2, 1, 4, 3, false),
                        new TextureAtlasPlacement("texture-b", 0, 8, 1, 2, 2, false),
                        new TextureAtlasPlacement("texture-unused", 0, 12, 1, 1, 1, false)));
        assertEquals(TextureAtlasLayoutProvider.ApplyOutcome.APPLIED, provider.apply(before, plan));
        assertEquals(plan, provider.current().orElseThrow().currentPlan());
        assertEquals(1, fixture.document.dirtyCount);
        capture.close();
        assertTrue(provider.current().isEmpty(), "closed connection must not regain a model target");
        assertEquals(TextureAtlasLayoutProvider.ApplyOutcome.REJECTED, provider.apply(before, plan));
    }

    @Test
    void rejectsDirectTargetAfterDocumentReplacementEvenWhenGuidAndLayoutMatch() {
        new Fixture();
        final var provider = new VerifiedTextureAtlasLayoutProvider(
                resolver("5.3.02", true), "session-a", new TextureAtlasDataModelCapture(), PROFILE_5_3_02);
        final var before = provider.current().orElseThrow();
        final Fixture replacement = new Fixture();
        assertEquals(before.modelId(), provider.current().orElseThrow().modelId());
        assertEquals(TextureAtlasLayoutProvider.ApplyOutcome.REJECTED, provider.apply(before, before.currentPlan()));
        assertEquals(0, replacement.data.applyCount);
    }

    @Test
    void noAtlasOrActiveDocumentDoesNotIssueDirectTarget() {
        final Fixture fixture = new Fixture();
        final var provider = new VerifiedTextureAtlasLayoutProvider(
                resolver("5.3.02", true), "session-a", new TextureAtlasDataModelCapture(), PROFILE_5_3_02);
        fixture.source.textureManager.atlases.clear();
        assertTrue(provider.current().isEmpty());
        Host.document = null;
        assertTrue(provider.current().isEmpty());
    }

    @Test
    void undoRedoPreservesRawImageDisplaySelectedAfterLayout() {
        final Fixture fixture = new Fixture();
        final var provider = provider(resolver("5.3.02", true), "session-a", fixture);
        final var before = provider.current().orElseThrow();
        final var plan = new TextureAtlasLayoutPlan(
                16,
                8,
                1,
                List.of(
                        new TextureAtlasPlacement("texture-a", 0, 2, 1, 4, 3, false),
                        new TextureAtlasPlacement("texture-b", 0, 8, 1, 2, 2, false),
                        new TextureAtlasPlacement("texture-unused", 0, 12, 1, 1, 1, false)));
        assertEquals(TextureAtlasLayoutProvider.ApplyOutcome.APPLIED, provider.apply(before, plan));
        final var manager = fixture.source.textureManager;
        assertFalse(manager.modelImageMode);
        // SHOW_MODEL_BY_RAW_IMAGE changes native display mode without adding an Undo entry.
        manager.modelImageMode = true;
        for (int cycle = 0; cycle < 3; cycle++) {
            fixture.document.editMode.undo();
            assertTrue(manager.modelImageMode, "Undo must not force atlas display back on");
            fixture.document.editMode.redo();
            assertTrue(manager.modelImageMode, "Redo must preserve the selected display mode");
        }
        assertEquals(6, manager.modelImageInputChangeCount);
        assertEquals(1, manager.atlasInputChangeCount);
    }

    @Test
    void projectsCurrentAtlasAndAppliesACompletePlanThroughNativeAtlasTransaction() {
        final Fixture fixture = new Fixture();
        final VerifiedTextureAtlasLayoutProvider provider = provider(resolver("5.3.02", true), "session-a", fixture);

        final TextureAtlasAuthoringState current = provider.current().orElseThrow();
        assertEquals("session-a", current.documentId());
        assertEquals("model-a", current.modelId());
        assertEquals("atlas-a", current.atlasId());
        assertEquals(List.of("atlas-a"), current.currentPlan().pageNames());
        assertEquals(3, current.items().size());
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
                fixture.source.textureManager.atlases.get(0),
                fixture.source.textureManager.atlases.get(0).entries.get(0).atlas);
        assertEquals(
                fixture.source.textureManager.atlases.get(1),
                fixture.source.textureManager.atlases.get(1).entries.get(0).atlas);
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
        assertEquals(
                List.of("Turboism Atlas 1", "Turboism Atlas 2"),
                updated.currentPlan().pageNames());
    }

    @Test
    void preservesAnAtlasEntryWhoseDrawableUseIsTemporarilyAbsent() {
        final Fixture fixture = new Fixture();
        final VerifiedTextureAtlasLayoutProvider provider = provider(resolver("5.3.02", true), "session-a", fixture);

        final TextureAtlasAuthoringState current = provider.current().orElseThrow();
        assertEquals(
                List.of("texture-a", "texture-b", "texture-unused"),
                current.items().stream().map(item -> item.textureId()).toList());
        assertEquals(
                List.of("texture-a", "texture-b", "texture-unused"),
                current.currentPlan().placements().stream()
                        .map(TextureAtlasPlacement::textureId)
                        .toList());

        final TextureAtlasLayoutPlan replacement = new TextureAtlasLayoutPlan(
                16,
                8,
                1,
                List.of(
                        new TextureAtlasPlacement("texture-a", 0, 2, 1, 4, 3, false),
                        new TextureAtlasPlacement("texture-b", 0, 8, 1, 2, 2, false),
                        new TextureAtlasPlacement("texture-unused", 0, 12, 1, 1, 1, false)));
        assertEquals(TextureAtlasLayoutProvider.ApplyOutcome.APPLIED, provider.apply(current, replacement));
        assertEquals(
                List.of("texture-a", "texture-b", "texture-unused"),
                fixture.source.textureManager.atlases.get(0).entries.stream()
                        .map(entry -> entry.image.guid.value)
                        .toList());
    }

    @Test
    void appliesExplicitNativeAtlasPageNames() {
        final Fixture fixture = new Fixture();
        final VerifiedTextureAtlasLayoutProvider provider = provider(resolver("5.3.02", true), "session-a", fixture);
        final TextureAtlasAuthoringState current = provider.current().orElseThrow();
        final TextureAtlasLayoutPlan plan = new TextureAtlasLayoutPlan(
                16,
                8,
                2,
                List.of("Restored Page A", "Restored Page B"),
                List.of(
                        new TextureAtlasPlacement("texture-a", 0, 2, 1, 4, 3, false),
                        new TextureAtlasPlacement("texture-b", 1, 5, 2, 2, 2, false)));

        assertEquals(TextureAtlasLayoutProvider.ApplyOutcome.APPLIED, provider.apply(current, plan));
        assertEquals(
                List.of("Restored Page A", "Restored Page B"),
                fixture.source.textureManager.atlases.stream().map(Atlas::name).toList());
        assertEquals(plan, provider.current().orElseThrow().currentPlan());
    }

    @Test
    void rejectsStalePlanningStateAndUnsupportedIdentityBeforeMutation() {
        final Fixture fixture = new Fixture();
        final VerifiedTextureAtlasLayoutProvider provider = provider(resolver("5.3.02", true), "session-a", fixture);
        final TextureAtlasAuthoringState stale = provider.current().orElseThrow();
        fixture.replaceAtlasWithSameId();

        assertEquals(TextureAtlasLayoutProvider.ApplyOutcome.REJECTED, provider.apply(stale, stale.currentPlan()));
        assertEquals(0, fixture.data.applyCount);
        assertFalse(provider(resolver("5.2.03", true), "session-a", fixture)
                .current()
                .isPresent());
        assertFalse(new VerifiedTextureAtlasLayoutProvider(
                        resolver("5.3.02", true), "session-a", captured(fixture), PROFILE_5_2_03)
                .current()
                .isPresent());
        assertFalse(provider(resolver("5.3.02", false), "session-a", fixture)
                .current()
                .isPresent());
    }

    @Test
    void usesActiveModelInsteadOfCapturedAtlasDataFromABackgroundDocument() {
        final Fixture captured = new Fixture();
        final VerifiedTextureAtlasLayoutProvider provider = provider(resolver("5.3.02", true), "session-a", captured);
        final var oldTarget = provider.current().orElseThrow();
        final Fixture active = new Fixture();

        final var current = provider.current().orElseThrow();
        assertFalse(current.documentId().equals(oldTarget.documentId()));
        assertEquals(
                TextureAtlasLayoutProvider.ApplyOutcome.REJECTED, provider.apply(oldTarget, oldTarget.currentPlan()));
        final var moved = new TextureAtlasLayoutPlan(
                16,
                8,
                1,
                List.of(
                        new TextureAtlasPlacement("texture-a", 0, 2, 1, 4, 3, false),
                        new TextureAtlasPlacement("texture-b", 0, 8, 1, 2, 2, false),
                        new TextureAtlasPlacement("texture-unused", 0, 12, 1, 1, 1, false)));
        assertEquals(TextureAtlasLayoutProvider.ApplyOutcome.APPLIED, provider.apply(current, moved));
        assertEquals(0, captured.data.applyCount);
        assertEquals(1, active.data.applyCount);
        assertEquals(active.document, Host.document);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void stagedConstructionFailureLeavesEditorStateUntouched(final String version) {
        final Fixture fixture = new Fixture();
        fixture.data.failAtlasName = "Turboism Atlas 2";
        final VerifiedTextureAtlasSelectorContract.Profile profile =
                VerifiedTextureAtlasSelectorContract.profileFor(version).orElseThrow();
        final VerifiedTextureAtlasLayoutProvider provider =
                provider(resolver(version, true), "session-a", fixture, profile);
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

    @Test
    void failedRedoRollsBackTheEditorTransaction() {
        final Fixture fixture = new Fixture();
        fixture.data.failRedo = true;
        final VerifiedTextureAtlasLayoutProvider provider = provider(resolver("5.3.02", true), "session-a", fixture);
        final TextureAtlasAuthoringState current = provider.current().orElseThrow();
        final TextureAtlasLayoutPlan plan = new TextureAtlasLayoutPlan(
                16,
                8,
                1,
                List.of(
                        new TextureAtlasPlacement("texture-a", 0, 2, 1, 4, 3, false),
                        new TextureAtlasPlacement("texture-b", 0, 8, 1, 2, 2, false)));

        assertThrows(RuntimeException.class, () -> provider.apply(current, plan));
        assertEquals(List.of(true), fixture.document.editMode.rollbacks);
        assertEquals(0, fixture.source.refreshCount);
        assertEquals(0, fixture.document.repaintCount);
        assertEquals(0, fixture.document.dirtyCount);
    }

    @Test
    void rejectedRefreshListenerRollsBackBeforeRedo() {
        final Fixture fixture = new Fixture();
        fixture.data.rejectListener = true;
        final VerifiedTextureAtlasLayoutProvider provider = provider(resolver("5.3.02", true), "session-a", fixture);
        final TextureAtlasAuthoringState current = provider.current().orElseThrow();
        final TextureAtlasLayoutPlan plan = new TextureAtlasLayoutPlan(
                16,
                8,
                1,
                List.of(
                        new TextureAtlasPlacement("texture-a", 0, 2, 1, 4, 3, false),
                        new TextureAtlasPlacement("texture-b", 0, 8, 1, 2, 2, false)));

        assertThrows(IllegalStateException.class, () -> provider.apply(current, plan));
        assertEquals(
                List.of("atlas-a"),
                fixture.source.textureManager.atlases.stream().map(Atlas::name).toList());
        assertEquals(1, fixture.data.applyCount);
        assertEquals(List.of(true), fixture.document.editMode.rollbacks);
        assertEquals(0, fixture.source.refreshCount);
        assertEquals(0, fixture.document.repaintCount);
        assertEquals(0, fixture.document.dirtyCount);
    }

    @Test
    void refreshFailureRollsBackAndDoesNotMarkDirty() {
        final Fixture fixture = new Fixture();
        fixture.data.failRefresh = true;
        final VerifiedTextureAtlasLayoutProvider provider = provider(resolver("5.3.02", true), "session-a", fixture);
        final TextureAtlasAuthoringState current = provider.current().orElseThrow();
        final TextureAtlasLayoutPlan plan = new TextureAtlasLayoutPlan(
                16,
                8,
                1,
                List.of(
                        new TextureAtlasPlacement("texture-a", 0, 2, 1, 4, 3, false),
                        new TextureAtlasPlacement("texture-b", 0, 8, 1, 2, 2, false)));

        assertThrows(RuntimeException.class, () -> provider.apply(current, plan));
        assertEquals(
                List.of("atlas-a"),
                fixture.source.textureManager.atlases.stream().map(Atlas::name).toList());
        assertEquals(List.of(true), fixture.document.editMode.rollbacks);
        assertEquals(2, fixture.source.refreshCount);
        assertEquals(0, fixture.document.repaintCount);
        assertEquals(0, fixture.document.dirtyCount);
    }

    @Test
    void dirtyMarkFailureRollsBackAndRefreshesTheRestoredLayout() {
        final Fixture fixture = new Fixture();
        fixture.data.failDirty = true;
        final VerifiedTextureAtlasLayoutProvider provider = provider(resolver("5.3.02", true), "session-a", fixture);
        final TextureAtlasAuthoringState current = provider.current().orElseThrow();
        final TextureAtlasLayoutPlan plan = new TextureAtlasLayoutPlan(
                16,
                8,
                1,
                List.of(
                        new TextureAtlasPlacement("texture-a", 0, 2, 1, 4, 3, false),
                        new TextureAtlasPlacement("texture-b", 0, 8, 1, 2, 2, false)));

        assertThrows(RuntimeException.class, () -> provider.apply(current, plan));
        assertEquals(
                List.of("atlas-a"),
                fixture.source.textureManager.atlases.stream().map(Atlas::name).toList());
        assertEquals(List.of(true), fixture.document.editMode.rollbacks);
        assertEquals(2, fixture.source.refreshCount);
        assertEquals(2, fixture.document.repaintCount);
        assertEquals(0, fixture.document.dirtyCount);
        assertEquals(current.revision(), provider.current().orElseThrow().revision());
    }

    @Test
    void endEditFailureDoesNotPublishTheSyntheticRevision() {
        final Fixture fixture = new Fixture();
        fixture.data.failEnd = true;
        final VerifiedTextureAtlasLayoutProvider provider = provider(resolver("5.3.02", true), "session-a", fixture);
        final TextureAtlasAuthoringState current = provider.current().orElseThrow();
        final TextureAtlasLayoutPlan plan = new TextureAtlasLayoutPlan(
                16,
                8,
                1,
                List.of(
                        new TextureAtlasPlacement("texture-a", 0, 2, 1, 4, 3, false),
                        new TextureAtlasPlacement("texture-b", 0, 8, 1, 2, 2, false)));

        assertThrows(RuntimeException.class, () -> provider.apply(current, plan));
        assertEquals(plan, provider.current().orElseThrow().currentPlan());
        assertEquals(current.revision(), provider.current().orElseThrow().revision());
        assertEquals(1, fixture.source.refreshCount);
        assertEquals(1, fixture.document.repaintCount);
        assertEquals(1, fixture.document.dirtyCount);
    }

    private static VerifiedTextureAtlasLayoutProvider provider(
            final VerifiedMemberResolver resolver, final String sessionIdentity, final Fixture fixture) {
        return VerifiedTextureAtlasFixture.provider(resolver, sessionIdentity, fixture, PROFILE_5_3_02);
    }

    private static VerifiedTextureAtlasLayoutProvider provider(
            final VerifiedMemberResolver resolver,
            final String sessionIdentity,
            final Fixture fixture,
            final VerifiedTextureAtlasSelectorContract.Profile profile) {
        return VerifiedTextureAtlasFixture.provider(resolver, sessionIdentity, fixture, profile);
    }
}
