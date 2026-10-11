package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.selector.EditorTextureSelectorContract;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class TextureAuthoringManifestTest {
    @Test
    void exact52AdmitsItsPreparedNativeRawImageUndoRoute() {
        assertTrue(EditorModelVerificationManifest.cubism52Aliases()
                .containsAll(EditorTextureSelectorContract.REMOVE_RAW_IMAGE_5203_ALIASES));
        assertTrue(Collections.disjoint(
                EditorModelVerificationManifest.cubism52Aliases(),
                EditorTextureSelectorContract.REMOVE_RAW_IMAGE_ALIASES));
    }

    @Test
    void exact53VersionsKeepTheHandlerRouteAlongsideVerifiedSharedMembers() {
        // The handler stays the distinct 5.3 removal entry point on both records.
        for (Set<String> aliases : List.of(
                EditorModelVerificationManifest.cubism5302Aliases(),
                EditorModelVerificationManifest.cubism5303StaticAliases())) {
            assertTrue(aliases.containsAll(EditorTextureSelectorContract.REMOVE_RAW_IMAGE_ALIASES));
        }
        // 5.3.02 additionally binds the complete verified 5.2 prepared-Undo route.
        final Set<String> shared5302 = new HashSet<>(EditorModelVerificationManifest.cubism5302Aliases());
        shared5302.retainAll(EditorTextureSelectorContract.REMOVE_RAW_IMAGE_5203_ALIASES);
        assertEquals(EditorTextureSelectorContract.REMOVE_RAW_IMAGE_5203_ALIASES, shared5302);
        // 5.3.03's roster keeps only the two shared reads; the 5.2 mutation factories
        // and force-redo entry point stay out of its required set.
        final Set<String> shared5303 = new HashSet<>(EditorModelVerificationManifest.cubism5303StaticAliases());
        shared5303.retainAll(EditorTextureSelectorContract.REMOVE_RAW_IMAGE_5203_ALIASES);
        assertEquals(
                Set.of(
                        "cubism.editor-model.model-image.input-filter-env",
                        "cubism.editor-model.model-image-filter-env.layer-input-data"),
                shared5303);
    }

    @Test
    void everyWriterRequiresTheVerifiedCompensationRoute() {
        assertTrue(
                EditorTextureSelectorContract.WRITE_REQUIRED_ALIASES.contains("cubism.editor-model.texture-undo.undo"));
        for (Set<String> aliases : List.of(
                EditorModelVerificationManifest.cubism52Aliases(),
                EditorModelVerificationManifest.cubism5302Aliases(),
                EditorModelVerificationManifest.cubism5303StaticAliases())) {
            assertTrue(aliases.containsAll(EditorTextureSelectorContract.WRITE_REQUIRED_ALIASES));
        }
    }
}
