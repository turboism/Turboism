package dev.turboism.mapping.verification.selector;

import java.util.Set;

/** Exact additional selector contract for Editor parameter-definition writes. */
public final class EditorParameterDefinitionWriteSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID =
        "cubism.editor-model.parameter-definition.write";
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.main-frame",
        "cubism.editor-model.main-frame.parameter-palette",
        "cubism.editor-model.parameter-palette.view",
        "cubism.editor-model.parameter-palette-view.operation",
        "cubism.editor-model.parameter-operation.class",
        "cubism.editor-model.parameter-operation.property-editor",
        "cubism.editor-model.parameter-operation.validator",
        "cubism.editor-model.parameter-operation.refresh",
        "cubism.editor-model.parameter-property-editor.class",
        "cubism.editor-model.parameter-property-editor.update-definition",
        "cubism.editor-model.parameter-property-editor.rebuild-keep-value",
        "cubism.editor-model.parameter-validator.class",
        "cubism.editor-model.parameter-validator.valid-id",
        "cubism.editor-model.parameter-validator.supports-type",
        "cubism.editor-model.parameter-validator.reject-type-change",
        "cubism.editor-model.parameter-validator.allow-repeat",
        "cubism.editor-model.parameter-validator.keys-outside-range",
        "cubism.editor-model.parameter-validator.default-change-affects-morph-target",
        "cubism.editor-model.parameter-helper-owner.class",
        "cubism.editor-model.parameter-helper.class",
        "cubism.editor-model.parameter-helper.instance",
        "cubism.editor-model.parameter-helper.morph-target-eligible",
        "cubism.editor-model.parameter-source.model-source",
        "cubism.editor-model.model-source.all-objects",
        "cubism.editor-model.parameter-controllable.class",
        "cubism.editor-model.parameter-controllable.keyform-grid",
        "cubism.editor-model.keyform-grid.class",
        "cubism.editor-model.keyform-grid.contains-parameter",
        "cubism.editor-model.parameter-controllable.morph-target-set",
        "cubism.editor-model.morph-target-set.class",
        "cubism.editor-model.morph-target-set.contains-parameter",
        "cubism.editor-model.parameter-source.guid",
        "cubism.editor-model.parameter-refresh-callback.create"
    );

    private EditorParameterDefinitionWriteSelectorContract() {
    }
}
