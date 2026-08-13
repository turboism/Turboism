package dev.turboism.mapping.verification;

import java.util.Set;

/**
 * Exact additive selector contract for Editor animation document reads.
 *
 * <p>Evidence (exact public class-file observation, Cubism 5.2.03 and 5.3.02):
 * {@code CModelingDocument.getFileContentDocs()} returns the file-content
 * documents, which include {@code CAnimationFileContent}; its
 * {@code getAnimation()} exposes the animation name and scene list, and
 * {@code getCurrentScene()} exposes the current scene name. Scene names are
 * read through {@code CSceneSource.getSceneName()}.</p>
 */
public final class EditorAnimationReadSelectorContract {

    public static final String CUBISM_VERSION = "5.3.02";

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";

    public static final String CAPABILITY_ID = "cubism.editor-model.animation.read";

    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.modeling-document.file-content-docs",
        "cubism.editor-model.animation-file-content.class",
        "cubism.editor-model.animation-file-content.animation",
        "cubism.editor-model.animation.class",
        "cubism.editor-model.animation.name",
        "cubism.editor-model.animation.scenes",
        "cubism.editor-model.animation.current-scene",
        "cubism.editor-model.scene-source.class",
        "cubism.editor-model.scene-source.scene-name"
    );

    private EditorAnimationReadSelectorContract() {
    }
}
