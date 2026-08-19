package dev.turboism.mapping.verification;

import dev.turboism.mapping.verification.selector.CoreMocInfoSelectorContract;
import dev.turboism.mapping.verification.selector.EditorAnimationReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorAutoYureReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorModelNameWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPhysicsReadSelectorContract;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the additive selector contracts of the Wave-1 evaluated join and the
 * read-only document family (auto-Yure, physics, animation) plus the
 * model-name write and Core MOC-info contracts.
 */
class EvaluatedJoinSelectorContractTest {

    @Test
    void autoYureReadContractCoversTheDocumentModelPath() {
        final var aliases = EditorAutoYureReadSelectorContract.REQUIRED_ALIASES;
        assertEquals(28, aliases.size());
        assertTrue(aliases.contains("cubism.editor-model.parameter-controllable-source.extensions"));
        assertTrue(aliases.contains("cubism.editor-model.auto-yure-config-extension.param-to-config-map"));
        assertTrue(aliases.contains("cubism.editor-model.auto-yure-config.left"));
        assertTrue(aliases.contains("cubism.editor-model.auto-yure-config-root-direction.top"));
        assertTrue(aliases.contains("cubism.editor-model.yure-deform-config.decay-level"));
        assertTrue(aliases.contains("cubism.editor-model.parameter-controllable-source.id"));
        assertThrows(UnsupportedOperationException.class, () -> aliases.add("bad"));
    }

    @Test
    void physicsReadContractCoversTheSettingsDocumentPath() {
        final var aliases = EditorPhysicsReadSelectorContract.REQUIRED_ALIASES;
        assertEquals(17, aliases.size());
        assertTrue(aliases.contains("cubism.editor-model.model-source.physics-settings-source-set"));
        assertTrue(aliases.contains("cubism.editor-model.physics-settings-source-set.gravity"));
        assertTrue(aliases.contains("cubism.editor-model.physics-settings-source.vertices"));
        assertTrue(aliases.contains("cubism.editor-model.vector2.x"));
    }

    @Test
    void animationReadContractCoversTheFileContentPath() {
        final var aliases = EditorAnimationReadSelectorContract.REQUIRED_ALIASES;
        assertEquals(9, aliases.size());
        assertTrue(aliases.contains("cubism.editor-model.modeling-document.file-content-docs"));
        assertTrue(aliases.contains("cubism.editor-model.animation-file-content.animation"));
        assertTrue(aliases.contains("cubism.editor-model.scene-source.scene-name"));
    }

    @Test
    void modelNameWriteContractCoversTheUndoEnvelope() {
        final var aliases = EditorModelNameWriteSelectorContract.REQUIRED_ALIASES;
        assertEquals(13, aliases.size());
        assertTrue(aliases.contains("cubism.editor-model.model-source.set-name"));
        assertTrue(aliases.contains("cubism.editor-model.simple-undo.create"));
        assertTrue(aliases.contains("cubism.editor-model.undo.add"));
        assertTrue(aliases.contains("cubism.editor-model.edit-mode.begin"));
        assertTrue(aliases.contains("cubism.editor-model.modeling-document.mark-dirty"));
    }

    @Test
    void coreMocInfoContractRoutesOnlyProfilesWithTheVersionRead() {
        assertEquals(
            java.util.Set.of(
                CoreMocInfoSelectorContract.MODEL_GET_MOC,
                CoreMocInfoSelectorContract.MOC_CLASS,
                CoreMocInfoSelectorContract.MOC_GET_MOC_VERSION
            ),
            CoreMocInfoSelectorContract.REQUIRED_ALIASES
        );
        assertEquals("adapter.core-model.readonly", CoreMocInfoSelectorContract.ADAPTER_SLICE_ID);
    }
}
