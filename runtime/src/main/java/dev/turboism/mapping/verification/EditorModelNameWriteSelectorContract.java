package dev.turboism.mapping.verification;

import java.util.Set;

/**
 * Exact additive selector contract for Editor model-name authoring writes.
 *
 * <p>Evidence (exact public class-file observation, Cubism 5.2.03 and 5.3.02):
 * {@code CModelSource.setName(String)} is the document-model name setter
 * ({@code CModelingDocument.setModelName} delegates to it). The write is
 * wrapped in the standard Editor transaction envelope (edit mode begin/end,
 * {@code SimpleUndo} over the {@code ICopyable} model source, undo registration,
 * dirty marking, and complete-pack refresh), so it never bypasses Undo.</p>
 */
public final class EditorModelNameWriteSelectorContract {

    public static final String CUBISM_VERSION = "5.3.02";

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";

    public static final String CAPABILITY_ID = "cubism.editor-model.model-name.write";

    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.model-source.set-name",
        "cubism.editor-model.model-source.class",
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.complete-pack",
        "cubism.editor-model.modeling-document.edit-mode",
        "cubism.editor-model.edit-mode.begin",
        "cubism.editor-model.edit-mode.end",
        "cubism.editor-model.simple-undo.create",
        "cubism.editor-model.undo.add",
        "cubism.editor-model.modeling-document.mark-dirty",
        "cubism.editor-model.complete-pack.update-parameter",
        "cubism.editor-model.complete-pack.repaint-canvas",
        "cubism.editor-model.model-source.update-instances"
    );

    private EditorModelNameWriteSelectorContract() {
    }
}
