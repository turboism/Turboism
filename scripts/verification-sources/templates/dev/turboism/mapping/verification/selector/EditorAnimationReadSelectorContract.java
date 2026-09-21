package dev.turboism.mapping.verification.selector;

import java.util.Set;

/**
 * Exact additive selector contract for Editor animation document reads.
 *
 * <p>Evidence (exact public class-file observation, Cubism 5.2.03, 5.3.02,
 * and 5.3.03): {@code CEAppCtrl.getCurrentProject()} exposes the active
 * {@code CEProject}; its {@code getChildren()} returns the project's
 * {@code ICProjectEntry} file contents, which include
 * {@code CAnimationFileContent} entries. Each animation file content exposes
 * {@code getAnimation()} with the animation name, scene list, and current
 * scene; scene names are read through {@code CSceneSource.getSceneName()}.</p>
 *
 * <p>An animation document is attributed to the model only when one of its
 * scene track trees contains a {@code CMvTrack_Live2DModel_Source} whose
 * {@code getModel()} is the bound {@code CModelSource} (identity, or equal
 * {@code CModelGuid} when the linked resource resolved to a distinct
 * instance).</p>
 */
public final class EditorAnimationReadSelectorContract {

    public static final String CUBISM_VERSION = "${record:cubism-5.3.02-editor-model.json:cubismVersion}";

    public static final String ADAPTER_SLICE_ID = "${record:cubism-5.2.03-editor-model.json:adapterSliceId}";

    public static final String CAPABILITY_ID = "cubism.editor-model.animation.read";

    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.app-controller.current-project",
        "cubism.editor-model.project.class",
        "cubism.editor-model.project.children",
        "cubism.editor-model.file-content.file",
        "cubism.editor-model.animation-file-content.class",
        "cubism.editor-model.animation-file-content.animation",
        "cubism.editor-model.animation.class",
        "cubism.editor-model.animation.name",
        "cubism.editor-model.animation.scenes",
        "cubism.editor-model.animation.current-scene",
        "cubism.editor-model.scene-source.class",
        "cubism.editor-model.scene-source.scene-name",
        "cubism.editor-model.scene-source.root-track",
        "cubism.editor-model.track-group.class",
        "cubism.editor-model.track-group.children",
        "cubism.editor-model.track-model.class",
        "cubism.editor-model.track-model.model",
        "cubism.editor-model.track-model.resource-ref",
        "cubism.editor-model.resource-file.src-file",
        "cubism.editor-model.model-source.class",
        "cubism.editor-model.model-source.guid",
        "cubism.editor-model.guid.value"
    );

    private EditorAnimationReadSelectorContract() {
    }
}
