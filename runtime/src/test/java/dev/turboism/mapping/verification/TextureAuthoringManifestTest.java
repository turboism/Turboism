package dev.turboism.mapping.verification;

import dev.turboism.mapping.verification.selector.EditorTextureSelectorContract;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
            assertTrue(Collections.disjoint(aliases, EditorTextureSelectorContract.REMOVE_RAW_IMAGE_5203_ALIASES));
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
