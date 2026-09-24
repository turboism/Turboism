package dev.turboism.mapping.verification.selector;

import java.util.Set;

/**
 * Exact additive contract for reading the live Editor object selection.
 *
 * <p>Covers the update-manager selection GUID list plus the object/parameter source
 * enumerations needed to project host GUIDs onto SDK object ids. Active-palette focus
 * fields are intentionally not part of this contract: no reviewed selector proves them,
 * so {@code HostSelection.active*Id} stays empty rather than guessing the first entry.
 */
public final class EditorSelectionReadSelectorContract {

    public static final String ADAPTER_SLICE_ID = EditorObjectReadSelectorContract.ADAPTER_SLICE_ID;
    public static final String CAPABILITY_ID = "cubism.editor-model.selection.read";

    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.current-document",
        "cubism.editor-model.app-controller.update-manager",
        "cubism.editor-model.update-manager.selection-guid-list",
        "cubism.editor-model.modeling-document.class",
        "cubism.editor-model.modeling-document.model-source",
        "cubism.editor-model.model-source.all-objects",
        "cubism.editor-model.model-source.all-parameters",
        "cubism.editor-model.parameter-controllable-source.guid",
        "cubism.editor-model.parameter-controllable-source.id",
        "cubism.editor-model.parameter-source.guid",
        "cubism.editor-model.parameter-source.id",
        "cubism.editor-model.guid.value",
        "cubism.editor-model.id.value"
    );

    private EditorSelectionReadSelectorContract() {
    }
}
