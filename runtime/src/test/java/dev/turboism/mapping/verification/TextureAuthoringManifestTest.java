package dev.turboism.mapping.verification;

import dev.turboism.mapping.verification.selector.EditorTextureSelectorContract;
import dev.turboism.mapping.verification.selector.EditorTextureRelationsSelectorContract;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class TextureAuthoringManifestTest {
    @Test
    void exact52AdmitsItsPreparedNativeRawImageUndoRoute() {
        assertTrue(EditorModelVerificationManifest.cubism52Aliases()
            .containsAll(EditorTextureSelectorContract.REMOVE_RAW_IMAGE_5203_ALIASES));
        assertTrue(Collections.disjoint(EditorModelVerificationManifest.cubism52Aliases(),
            EditorTextureSelectorContract.REMOVE_RAW_IMAGE_ALIASES));
    }

    @Test
    void exact53VersionsKeepTheHandlerRouteWithoutBorrowing52Factories() {
        for (Set<String> aliases : List.of(EditorModelVerificationManifest.cubism5302Aliases(),
            EditorModelVerificationManifest.cubism5303StaticAliases())) {
            assertTrue(aliases.containsAll(EditorTextureSelectorContract.REMOVE_RAW_IMAGE_ALIASES));
            final Set<String> sharedAliases = new HashSet<>(aliases);
            sharedAliases.retainAll(EditorTextureSelectorContract.REMOVE_RAW_IMAGE_5203_ALIASES);
            // 5.3.02 relation reads share these two reads with the 5.2 removal route,
            // but must not admit its mutation factories or force-redo entry point.
            final Set<String> expectedReads = aliases.equals(EditorModelVerificationManifest.cubism5302Aliases())
                ? Set.of("cubism.editor-model.model-image.input-filter-env",
                    "cubism.editor-model.model-image-filter-env.layer-input-data")
                : Set.of();
            assertEquals(expectedReads, sharedAliases);
            assertTrue(EditorTextureRelationsSelectorContract.REQUIRED_ALIASES.containsAll(sharedAliases));
        }
    }

    @Test
    void everyWriterRequiresTheVerifiedCompensationRoute() {
        assertTrue(EditorTextureSelectorContract.WRITE_REQUIRED_ALIASES.contains("cubism.editor-model.texture-undo.undo"));
        for (Set<String> aliases : List.of(EditorModelVerificationManifest.cubism52Aliases(),
            EditorModelVerificationManifest.cubism5302Aliases(), EditorModelVerificationManifest.cubism5303StaticAliases())) {
            assertTrue(aliases.containsAll(EditorTextureSelectorContract.WRITE_REQUIRED_ALIASES));
        }
    }
}
