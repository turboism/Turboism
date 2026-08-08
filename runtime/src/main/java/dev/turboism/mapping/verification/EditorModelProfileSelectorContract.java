package dev.turboism.mapping.verification;

import java.util.HashSet;
import java.util.Set;

/** Exact additive selector contract for Editor model name write, model profile read, and canvas projection. */
public final class EditorModelProfileSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String NAME_WRITE_CAPABILITY_ID = "cubism.editor-model.model-name.write";
    public static final String PROFILE_READ_CAPABILITY_ID = "cubism.editor-model.model-profile.read";

    public static final Set<String> NAME_WRITE_REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.current-document",
        "cubism.editor-model.app-controller.complete-pack",
        "cubism.editor-model.modeling-document.edit-mode",
        "cubism.editor-model.modeling-document.mark-dirty",
        "cubism.editor-model.edit-mode.begin",
        "cubism.editor-model.edit-mode.end",
        "cubism.editor-model.undo.add",
        "cubism.editor-model.undo.add-listener",
        "cubism.editor-model.undo-listener.class",
        "cubism.editor-model.model-source.class",
        "cubism.editor-model.model-source.name",
        "cubism.editor-model.model-source.set-name",
        "cubism.editor-model.model-source.update-instances",
        "cubism.editor-model.complete-pack.update-part-palette",
        "cubism.editor-model.complete-pack.repaint-canvas",
        "cubism.editor-model.simple-undo.create"
    );

    public static final Set<String> PROFILE_READ_REQUIRED_ALIASES = profileAliases();

    private static Set<String> profileAliases() {
        final HashSet<String> aliases = new HashSet<>(Set.of(
            "cubism.editor-model.model-source.model-info",
            "cubism.editor-model.model-info.class",
            "cubism.editor-model.model-info.origin",
            "cubism.editor-model.model-info.pixels-per-unit",
            "cubism.editor-model.point.class",
            "cubism.editor-model.point.x",
            "cubism.editor-model.point.y",
            "cubism.editor-model.model-source.canvas",
            "cubism.editor-model.image-canvas.class",
            "cubism.editor-model.image-canvas.width",
            "cubism.editor-model.image-canvas.height"
        ));
        return Set.copyOf(aliases);
    }

    private EditorModelProfileSelectorContract() {
    }
}
