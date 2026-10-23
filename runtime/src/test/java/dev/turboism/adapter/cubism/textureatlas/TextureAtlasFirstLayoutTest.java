package dev.turboism.adapter.cubism.textureatlas;

import static dev.turboism.adapter.cubism.textureatlas.VerifiedTextureAtlasFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.textureatlas.*;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TextureAtlasFirstLayoutTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void firstLayoutCreatesPagesWithoutOpeningTheEditorAndUndoesBackToNoAtlas(boolean capturedEditor) {
        var fixture = importedModel();
        var provider = providerFor(fixture, capturedEditor);
        var before = provider.current().orElseThrow();
        assertEquals(2, before.items().size());
        assertTrue(fixture.source.textureManager.atlases.isEmpty(), "Reading must not create an empty page");
        assertTrue(fixture.document.editMode.edits.isEmpty());
        assertEquals(before, provider.current().orElseThrow());

        assertEquals(TextureAtlasLayoutProvider.ApplyOutcome.APPLIED, provider.apply(before, plan()));
        assertEquals(
                plan().placements(),
                provider.current().orElseThrow().currentPlan().placements());
        assertEquals(1, fixture.document.dirtyCount);
        assertTrue(
                fixture.source.textureManager.atlases.get(0).textureGenerated,
                "One-click layout must generate the texture without a manual editor confirmation");
        assertEquals(1, fixture.document.editMode.undos.size());
        assertEquals(List.of(false), fixture.document.editMode.rollbacks);
        fixture.document.editMode.undo();
        assertTrue(fixture.source.textureManager.atlases.isEmpty());
        assertEquals(before.items(), provider.current().orElseThrow().items());
        fixture.document.editMode.redo();
        assertEquals(
                plan().placements(),
                provider.current().orElseThrow().currentPlan().placements());
        assertEquals(TextureAtlasLayoutProvider.ApplyOutcome.REJECTED, provider.apply(before, plan()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedFirstLayoutLeavesNoPagesOrSuccessfulEdit(boolean capturedEditor) {
        var fixture = importedModel();
        var provider = providerFor(fixture, capturedEditor);
        var before = provider.current().orElseThrow();
        fixture.data.rejectListener = true;
        assertThrows(IllegalStateException.class, () -> provider.apply(before, plan()));
        assertTrue(fixture.source.textureManager.atlases.isEmpty());
        assertEquals(0, fixture.document.dirtyCount);
        assertTrue(fixture.document.editMode.undos.isEmpty());
        assertEquals(List.of(true), fixture.document.editMode.rollbacks);
        assertEquals(before, provider.current().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void textureGenerationFailureLeavesTheOriginalAtlasUntouched(boolean capturedEditor) {
        var fixture = new Fixture(false);
        var provider = providerFor(fixture, capturedEditor);
        var before = provider.current().orElseThrow();
        fixture.data.failTextureGeneration = true;
        var failure = assertThrows(
                dev.turboism.mapping.verification.VerifiedAccessException.class, () -> provider.apply(before, plan()));
        assertEquals("cubism.texture-atlas.atlas.update-texture", failure.alias());
        assertEquals(before, provider.current().orElseThrow());
        assertTrue(fixture.document.editMode.edits.isEmpty());
        assertEquals(0, fixture.document.dirtyCount);
    }

    private static Fixture importedModel() {
        var fixture = new Fixture(false);
        fixture.source.textureManager.atlases.clear();
        fixture.data.atlases.clear();
        return fixture;
    }

    private static VerifiedTextureAtlasLayoutProvider providerFor(Fixture fixture, boolean capturedEditor) {
        return new VerifiedTextureAtlasLayoutProvider(
                resolver("5.3.02", true),
                "session",
                capturedEditor ? captured(fixture) : new TextureAtlasDataModelCapture(),
                PROFILE_5_3_02);
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
