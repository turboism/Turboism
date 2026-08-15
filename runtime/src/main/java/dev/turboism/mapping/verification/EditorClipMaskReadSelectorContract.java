package dev.turboism.mapping.verification;

import java.util.Set;

/**
 * Exact additive selector contract for the ordered Editor ArtMesh clip-mask
 * read. Kept separate from the object-read contract so the clip read has its
 * own minimal capability gate instead of borrowing object reads plus a bare
 * alias. The inversion alias is included as the semantic dependency of the
 * clip-mask relationship (same source, ordered masks plus inversion).
 */
public final class EditorClipMaskReadSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = "cubism.editor-model.art-mesh-clip-mask.read";

    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.art-mesh-source.clip-guid-list",
        "cubism.editor-model.guid.value",
        "cubism.editor-model.art-mesh-source.inverted-mask"
    );

    private EditorClipMaskReadSelectorContract() {
    }
}
