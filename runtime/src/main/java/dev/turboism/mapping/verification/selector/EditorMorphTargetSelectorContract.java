package dev.turboism.mapping.verification.selector;

import java.util.HashSet;
import java.util.Set;

/** Exact additive selector contract for Editor keyform Morph Target read and binding write. */
public final class EditorMorphTargetSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String READ_CAPABILITY_ID = "cubism.editor-model.morph-target.read";
    public static final String WRITE_CAPABILITY_ID = "cubism.editor-model.morph-target.write";

    public static final Set<String> READ_REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.parameter-controllable.morph-target-set",
        "cubism.editor-model.morph-target-set.class",
        "cubism.editor-model.morph-target-set.morph-targets",
        "cubism.editor-model.morph-target.class",
        "cubism.editor-model.morph-target.parameter-guid",
        "cubism.editor-model.morph-target.key-value",
        "cubism.editor-model.morph-target.keyform-guid",
        "cubism.editor-model.model-source.parameter-source-set",
        "cubism.editor-model.parameter-source-set.class",
        "cubism.editor-model.parameter-source-set.get",
        "cubism.editor-model.parameter-source-set.get-by-id",
        "cubism.editor-model.parameter-source.id",
        "cubism.editor-model.parameter-source.guid",
        "cubism.editor-model.parameter-id.create",
        "cubism.editor-model.id.value",
        "cubism.editor-model.guid.value",
        "cubism.editor-model.form-guid.value"
    );

    public static final Set<String> WRITE_REQUIRED_ALIASES = writeAliases();

    private static Set<String> writeAliases() {
        final HashSet<String> aliases = new HashSet<>(READ_REQUIRED_ALIASES);
        aliases.addAll(Set.of(
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
            "cubism.editor-model.model-source.update-instances",
            "cubism.editor-model.complete-pack.update-part-palette",
            "cubism.editor-model.complete-pack.repaint-canvas",
            "cubism.editor-model.morph-target-utils.instance",
            "cubism.editor-model.morph-target.change-parameter"
        ));
        return Set.copyOf(aliases);
    }

    private EditorMorphTargetSelectorContract() {
    }
}
