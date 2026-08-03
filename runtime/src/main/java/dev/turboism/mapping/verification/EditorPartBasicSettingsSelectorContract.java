package dev.turboism.mapping.verification;

import java.util.HashSet;
import java.util.Set;

/** Exact additive selector contract for Editor Part basic settings. */
public final class EditorPartBasicSettingsSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String READ_CAPABILITY_ID =
        "cubism.editor-model.part-basic-settings.read";
    public static final String WRITE_CAPABILITY_ID =
        "cubism.editor-model.part-basic-settings.write";

    public static final Set<String> READ_REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.model-source.parts",
        "cubism.editor-model.model.parts",
        "cubism.editor-model.part.class",
        "cubism.editor-model.part.source",
        "cubism.editor-model.part-source.class",
        "cubism.editor-model.part-source.id",
        "cubism.editor-model.part-source.local-name",
        "cubism.editor-model.part-source.default-order",
        "cubism.editor-model.part-source.sketch",
        "cubism.editor-model.part-source.edit-color",
        "cubism.editor-model.part-id.value",
        "cubism.editor-model.parameter-controllable-source.visible",
        "cubism.editor-model.parameter-controllable-source.locked",
        "cubism.editor-model.parameter-controllable-source.visible-in-hierarchy",
        "cubism.editor-model.parameter-controllable-source.locked-in-hierarchy",
        "cubism.editor-model.color.class",
        "cubism.editor-model.color.red",
        "cubism.editor-model.color.green",
        "cubism.editor-model.color.blue",
        "cubism.editor-model.color.alpha"
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
            "cubism.editor-model.model-source.update-visible-lock-hierarchy",
            "cubism.editor-model.model-source.update-instances",
            "cubism.editor-model.part-source.set-local-name",
            "cubism.editor-model.part-source.set-default-order",
            "cubism.editor-model.part-source.set-sketch",
            "cubism.editor-model.part-source.set-edit-color",
            "cubism.editor-model.part-source.create-undo-for-basic-settings",
            "cubism.editor-model.parameter-controllable-source.set-visible",
            "cubism.editor-model.parameter-controllable-source.set-locked",
            "cubism.editor-model.color.create",
            "cubism.editor-model.complete-pack.update-part-palette",
            "cubism.editor-model.complete-pack.repaint-canvas"
        ));
        return Set.copyOf(aliases);
    }

    private EditorPartBasicSettingsSelectorContract() {
    }
}
