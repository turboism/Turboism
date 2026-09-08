package dev.turboism.mapping.verification.selector;

import java.util.Set;

/** Optional exact selector families for bounded native history semantic projection. */
public final class EditorHistorySemanticSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = "cubism.editor-history.semantic-read";

    public static final Set<String> GROUP_REQUIRED_ALIASES = Set.of(
        "cubism.editor-history.semantic.group.class",
        "cubism.editor-history.semantic.group.edits",
        "cubism.editor-history.semantic.group.count",
        "cubism.editor-history.entry.presentation-name"
    );

    public static final Set<String> PROPERTY_REQUIRED_ALIASES = Set.of(
        "cubism.editor-history.semantic.property.class",
        "cubism.editor-history.semantic.property.name",
        "cubism.editor-history.semantic.property.object",
        "cubism.editor-history.semantic.property.previous",
        "cubism.editor-history.semantic.property.post"
    );

    public static final Set<String> SIMPLE_REQUIRED_ALIASES = Set.of(
        "cubism.editor-history.semantic.simple.class",
        "cubism.editor-history.semantic.simple.target",
        "cubism.editor-history.semantic.simple.undo",
        "cubism.editor-history.semantic.simple.redo"
    );

    public static final Set<String> LIST_REQUIRED_ALIASES = Set.of(
        "cubism.editor-history.semantic.list.class",
        "cubism.editor-history.semantic.list.target",
        "cubism.editor-history.semantic.list.undo",
        "cubism.editor-history.semantic.list.redo"
    );

    public static final Set<String> ART_MESH_FORM_REQUIRED_ALIASES = Set.of(
        "cubism.editor-history.semantic.art-mesh-form.class",
        "cubism.editor-history.semantic.art-mesh-form.source",
        "cubism.editor-history.semantic.form.guid",
        "cubism.editor-model.form-guid.value",
        "cubism.editor-model.drawable-form.opacity",
        "cubism.editor-model.drawable-form.draw-order",
        "cubism.editor-model.drawable-form.multiply-color",
        "cubism.editor-model.drawable-form.screen-color",
        "cubism.editor-model.float-color.red",
        "cubism.editor-model.float-color.green",
        "cubism.editor-model.float-color.blue",
        "cubism.editor-model.parameter-controllable-source.id",
        "cubism.editor-model.parameter-controllable-source.local-name",
        "cubism.editor-model.id.value",
        "cubism.editor-model.parameter-controllable.keyform-grid",
        "cubism.editor-model.keyform-grid.bindings",
        "cubism.editor-history.semantic.keyform-grid.forms-for-guid",
        "cubism.editor-history.semantic.keyform-on-grid.class",
        "cubism.editor-history.semantic.keyform-on-grid.access-key",
        "cubism.editor-history.semantic.keyform-access-key.class",
        "cubism.editor-history.semantic.keyform-access-key.coordinates",
        "cubism.editor-history.semantic.key-on-parameter.class",
        "cubism.editor-history.semantic.key-on-parameter.binding",
        "cubism.editor-history.semantic.key-on-parameter.value",
        "cubism.editor-model.keyform-binding.class",
        "cubism.editor-history.semantic.keyform-binding.parameter",
        "cubism.editor-model.parameter-source.id",
        "cubism.editor-model.parameter-source.name"
    );

    public static final Set<String> ADD_REMOVE_REQUIRED_ALIASES = Set.of(
        "cubism.editor-history.semantic.add-remove.class",
        "cubism.editor-history.semantic.add-remove.owner",
        "cubism.editor-history.semantic.add-remove.index",
        "cubism.editor-history.semantic.add-remove.is-add"
    );

    public static final Set<String> ADD_REMOVE_PARAMETER_REQUIRED_ALIASES = Set.of(
        "cubism.editor-history.semantic.add-remove.parameter.class",
        "cubism.editor-history.semantic.add-remove.parameter.item",
        "cubism.editor-model.parameter-source.id",
        "cubism.editor-model.id.value"
    );

    public static final Set<String> ADD_REMOVE_PART_REQUIRED_ALIASES = Set.of(
        "cubism.editor-history.semantic.add-remove.part.class",
        "cubism.editor-history.semantic.add-remove.part.item",
        "cubism.editor-model.part-source.id",
        "cubism.editor-model.part-id.value"
    );

    public static final Set<String> ADD_REMOVE_DRAWABLE_REQUIRED_ALIASES = Set.of(
        "cubism.editor-history.semantic.add-remove.drawable.class",
        "cubism.editor-history.semantic.add-remove.drawable.item",
        "cubism.editor-model.parameter-controllable-source.id",
        "cubism.editor-model.id.value"
    );

    public static final Set<String> ADD_REMOVE_DEFORMER_REQUIRED_ALIASES = Set.of(
        "cubism.editor-history.semantic.add-remove.deformer.class",
        "cubism.editor-history.semantic.add-remove.deformer.item",
        "cubism.editor-model.parameter-controllable-source.id",
        "cubism.editor-model.id.value"
    );

    public static final Set<String> ADD_REMOVE_PARAMETER_GROUP_REQUIRED_ALIASES = Set.of(
        "cubism.editor-history.semantic.add-remove.parameter-group.class",
        "cubism.editor-history.semantic.add-remove.parameter-group.item",
        "cubism.editor-model.parameter-group.id",
        "cubism.editor-model.id.value"
    );

    private EditorHistorySemanticSelectorContract() {
    }
}
