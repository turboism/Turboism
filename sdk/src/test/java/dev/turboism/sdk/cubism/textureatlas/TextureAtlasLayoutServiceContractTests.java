package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.CubismFacade;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureAtlasLayoutServiceContractTests {

    @Test
    void facadeExposesDefaultPreviewLayoutServiceAccessor() throws Exception {
        final Method accessor = CubismFacade.class.getMethod("textureAtlasLayouts");

        assertEquals(TextureAtlasLayoutService.class, accessor.getReturnType());
        assertTrue(java.lang.reflect.Modifier.isPublic(accessor.getModifiers()));
        assertThrows(UnsupportedOperationException.class, () -> new NoOpFacade().textureAtlasLayouts());
    }

    @Test
    void snapshotIsImmutableAndCarriesCompletePlanningInput() {
        final TextureAtlasLayoutTarget target = new TextureAtlasLayoutTarget() { };
        final TextureAtlasLayoutConstraints constraints =
            new TextureAtlasLayoutConstraints(16, 8, 1, 1, 1, false, false);
        final List<TextureAtlasLayoutItem> items = List.of(
            new TextureAtlasLayoutItem("texture-a", 4, 3),
            new TextureAtlasLayoutItem("texture-b", 2, 2)
        );
        final TextureAtlasLayoutPlan current = new TextureAtlasLayoutPlan(
            16,
            8,
            1,
            List.of(
                new TextureAtlasPlacement("texture-a", 0, 1, 1, 4, 3, false),
                new TextureAtlasPlacement("texture-b", 0, 6, 1, 2, 2, false)
            )
        );

        final TextureAtlasLayoutSnapshot snapshot = new TextureAtlasLayoutSnapshot(
            target,
            "document-a",
            "model-a",
            "atlas-a",
            constraints,
            items,
            current
        );

        assertEquals(target, snapshot.target());
        assertEquals(items, snapshot.items());
        assertEquals(current, snapshot.currentPlan());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.items().clear());
        assertThrows(IllegalArgumentException.class, () -> new TextureAtlasLayoutSnapshot(
            target,
            "document-a",
            "model-a",
            "atlas-a",
            constraints,
            List.of(items.get(0), new TextureAtlasLayoutItem("texture-a", 1, 1)),
            current
        ));
    }

    @Test
    void applyResultHasExactlyOneSuccessStatusOrFailure() {
        final TextureAtlasLayoutApplyResult applied = TextureAtlasLayoutApplyResult.applied();
        final TextureAtlasLayoutApplyResult unchanged = TextureAtlasLayoutApplyResult.noChange();
        final TextureAtlasLayoutApplyResult failed = TextureAtlasLayoutApplyResult.failed(
            TextureAtlasLayoutFailureCode.TARGET_STALE,
            "The atlas target is stale."
        );

        assertEquals(Optional.of(TextureAtlasLayoutApplyStatus.APPLIED), applied.status());
        assertEquals(Optional.of(TextureAtlasLayoutApplyStatus.NO_CHANGE), unchanged.status());
        assertEquals(Optional.empty(), failed.status());
        assertEquals(Optional.of(TextureAtlasLayoutFailureCode.TARGET_STALE), failed.failureCode());
        assertTrue(failed.message().isPresent());
        assertThrows(IllegalArgumentException.class, () -> new TextureAtlasLayoutApplyResult(
            Optional.of(TextureAtlasLayoutApplyStatus.APPLIED),
            Optional.of(TextureAtlasLayoutFailureCode.PLAN_INVALID),
            Optional.of("invalid")
        ));
    }

    private static final class NoOpFacade implements CubismFacade {
        @Override public dev.turboism.sdk.cubism.CubismRuntimeSnapshot runtime() {
            return new dev.turboism.sdk.cubism.CubismRuntimeSnapshot(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                new dev.turboism.sdk.cubism.SelectionSnapshot(
                    List.of(), Optional.empty(), Optional.empty(), Optional.empty()
                ),
                List.of(), List.of(), List.of(), List.of()
            );
        }
        @Override public Optional<dev.turboism.sdk.cubism.ProjectSnapshot> activeProject() { return Optional.empty(); }
        @Override public Optional<dev.turboism.sdk.cubism.DocumentSnapshot> activeDocument() { return Optional.empty(); }
        @Override public Optional<dev.turboism.sdk.cubism.ModelSnapshot> activeModel() { return Optional.empty(); }
        @Override public boolean isHostPresent() { return false; }
        @Override public dev.turboism.sdk.cubism.transaction.TransactionManager transactionManager() {
            throw new UnsupportedOperationException();
        }
    }
}
