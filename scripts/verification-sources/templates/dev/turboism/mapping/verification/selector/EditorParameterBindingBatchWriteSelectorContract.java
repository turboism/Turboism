package dev.turboism.mapping.verification.selector;

import java.util.Set;

/** Exact additive selectors for atomic parameter-binding inversion and GUID transfer. */
public final class EditorParameterBindingBatchWriteSelectorContract {

    public static final String ADAPTER_SLICE_ID = EditorObjectReadSelectorContract.ADAPTER_SLICE_ID;
    public static final String INVERT_CAPABILITY_ID = "cubism.editor-model.parameter-bindings.invert";
    public static final String TRANSFER_CAPABILITY_ID = "cubism.editor-model.parameter-bindings.transfer";

    /** Exact selectors used by whole-binding GUID transfer. */
    public static final Set<String> TRANSFER_REQUIRED_ALIASES = Set.of(
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
        "cubism.editor-model.parameter-controllable-source.handler",
        "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit",
        "cubism.editor-model.parameter-controllable.keyform-grid",
        "cubism.editor-model.keyform-grid.bindings",
        "cubism.editor-model.keyform-binding.parameter-guid",
        "cubism.editor-model.guid.value",
        "cubism.editor-model.keyform-grid.find-binding",
        "cubism.editor-model.keyform-grid.reverse-parameter",
        "cubism.editor-model.keyform-grid.change-parameter",
        "cubism.editor-model.parameter.source",
        "cubism.editor-model.parameter-source.guid",
        "cubism.editor-model.model-source.update-instances",
        "cubism.editor-model.complete-pack.update-parameter",
        "cubism.editor-model.complete-pack.update-part-palette",
        "cubism.editor-model.complete-pack.update-deformer-palette",
        "cubism.editor-model.complete-pack.repaint-canvas"
    );

    /** Candidate-only selectors used to choose source/destination parameter ranges. */
    public static final Set<String> TRANSFER_VALIDATION_SETUP_REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.parameter-source.minimum",
        "cubism.editor-model.parameter-source.maximum"
    );

    /** Exact selectors used by the atomic inversion mutation itself. */
    public static final Set<String> INVERT_MUTATION_REQUIRED_ALIASES = Set.of(
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
        "cubism.editor-model.parameter-controllable-source.handler",
        "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit",
        "cubism.editor-model.parameter-controllable.keyform-grid",
        "cubism.editor-model.keyform-grid.bindings",
        "cubism.editor-model.keyform-binding.parameter-guid",
        "cubism.editor-model.keyform-grid.reverse-parameter",
        "cubism.editor-model.model-source.update-instances",
        "cubism.editor-model.complete-pack.update-parameter",
        "cubism.editor-model.complete-pack.update-part-palette",
        "cubism.editor-model.complete-pack.update-deformer-palette",
        "cubism.editor-model.complete-pack.repaint-canvas"
    );

    /** Candidate-only selectors for authoritative inversion keyform-order traces. */
    public static final Set<String> INVERT_VALIDATION_OBSERVATION_ALIASES = Set.of(
        "cubism.editor-model.keyform-grid.keyforms-on-grid",
        "cubism.editor-model.keyform-on-grid.form-guid",
        "cubism.editor-model.form-guid.value"
    );

    /** Complete exact selector union used by the task-scoped inversion candidate. */
    public static final Set<String> INVERT_REQUIRED_ALIASES = union(
        INVERT_MUTATION_REQUIRED_ALIASES,
        INVERT_VALIDATION_OBSERVATION_ALIASES
    );

    public static final Set<String> REQUIRED_ALIASES = Set.of(
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
        "cubism.editor-model.parameter-controllable-source.handler",
        "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit",
        "cubism.editor-model.parameter-controllable.keyform-grid",
        "cubism.editor-model.keyform-grid.bindings",
        "cubism.editor-model.keyform-binding.keys",
        "cubism.editor-model.keyform-binding.parameter-guid",
        "cubism.editor-model.keyform-grid.find-binding",
        "cubism.editor-model.keyform-grid.reverse-parameter",
        "cubism.editor-model.keyform-grid.change-parameter",
        "cubism.editor-model.keyform-grid.rearrange-keys",
        "cubism.editor-model.parameter.source",
        "cubism.editor-model.parameter-source.guid",
        "cubism.editor-model.parameter-source.morph-target",
        "cubism.editor-model.parameter-source.minimum",
        "cubism.editor-model.parameter-source.maximum",
        "cubism.editor-model.model-source.update-instances",
        "cubism.editor-model.complete-pack.update-parameter",
        "cubism.editor-model.complete-pack.update-part-palette",
        "cubism.editor-model.complete-pack.update-deformer-palette",
        "cubism.editor-model.complete-pack.repaint-canvas"
    );

    /** Candidate-only selectors for authoritative clamped-transfer keyform association traces. */
    public static final Set<String> CLAMPED_TRANSFER_VALIDATION_OBSERVATION_ALIASES = Set.of(
        "cubism.editor-model.parameter-controllable.keyform-grid",
        "cubism.editor-model.keyform-grid.keyforms-on-grid",
        "cubism.editor-model.keyform-on-grid.form-guid",
        "cubism.editor-model.form-guid.value",
        "cubism.editor-model.guid.value"
    );

    /** Candidate-only selectors for authoritative Morph Target transfer observations. */
    public static final Set<String> MORPH_TRANSFER_VALIDATION_OBSERVATION_ALIASES = Set.of(
        "cubism.editor-model.morph-target.keyform-guid",
        "cubism.editor-model.form-guid.value",
        "cubism.editor-model.guid.value",
        "cubism.editor-model.id.value"
    );

    /** Exact aliases used by the atomic whole-binding Morph Target transfer path. */
    public static final Set<String> MORPH_TRANSFER_REQUIRED_ALIASES = Set.of(
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
        "cubism.editor-model.complete-pack.update-parameter",
        "cubism.editor-model.complete-pack.update-part-palette",
        "cubism.editor-model.complete-pack.update-deformer-palette",
        "cubism.editor-model.complete-pack.repaint-canvas",
        "cubism.editor-model.parameter-controllable.keyform-grid",
        "cubism.editor-model.keyform-grid.find-binding",
        "cubism.editor-model.parameter-controllable.morph-target-set",
        "cubism.editor-model.morph-target-set.class",
        "cubism.editor-model.morph-target-set.morph-targets",
        "cubism.editor-model.morph-target.class",
        "cubism.editor-model.morph-target.parameter-guid",
        "cubism.editor-model.morph-target.key-value",
        "cubism.editor-model.model-source.parameter-source-set",
        "cubism.editor-model.parameter-source-set.class",
        "cubism.editor-model.parameter-source-set.get",
        "cubism.editor-model.parameter-source.id",
        "cubism.editor-model.id.value",
        "cubism.editor-model.parameter-source.guid",
        "cubism.editor-model.parameter-source.morph-target",
        "cubism.editor-model.parameter-source.minimum",
        "cubism.editor-model.parameter-source.maximum",
        "cubism.editor-model.morph-target-utils.instance",
        "cubism.editor-model.morph-target.change-parameter"
    );

    private static Set<String> union(final Set<String> left, final Set<String> right) {
        final java.util.LinkedHashSet<String> aliases = new java.util.LinkedHashSet<>(left);
        aliases.addAll(right);
        return Set.copyOf(aliases);
    }

    private EditorParameterBindingBatchWriteSelectorContract() {
    }
}
