package dev.turboism.mapping.verification;

import java.util.HashSet;
import java.util.Set;

/** Exact additive selector contract for Editor Parameter collection structure writes (create / copy / delete / folder moves). */
public final class EditorParameterStructureSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = "cubism.editor-model.parameter-structure.write";

    public static final Set<String> REQUIRED_ALIASES = aliases();

    private static Set<String> aliases() {
        final HashSet<String> aliases = new HashSet<>(Set.of(
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
            "cubism.editor-model.complete-pack.repaint-canvas",
            "cubism.editor-model.model-source.current-instance",
            "cubism.editor-model.model-source.handler",
            "cubism.editor-model.model-source.parameter-source-set",
            "cubism.editor-model.model-source.root-parameter-group",
            "cubism.editor-model.model-handler.class",
            "cubism.editor-model.model-handler.create-free-id-default",
            "cubism.editor-model.model-handler.remove-parameter",
            "cubism.editor-model.model-handler.move-parameter",
            "cubism.editor-model.parameter-source-set.class",
            "cubism.editor-model.parameter-source-set.get",
            "cubism.editor-model.parameter-source.class",
            "cubism.editor-model.parameter-source.create",
            "cubism.editor-model.parameter-source.id",
            "cubism.editor-model.parameter-source.guid",
            "cubism.editor-model.parameter-source.name",
            "cubism.editor-model.parameter-source.minimum",
            "cubism.editor-model.parameter-source.maximum",
            "cubism.editor-model.parameter-source.set-repeat",
            "cubism.editor-model.parameter-source.default",
            "cubism.editor-model.parameter-source.param-type",
            "cubism.editor-model.parameter-source.parent-group",
            "cubism.editor-model.parameter-source.type-normal",
            "cubism.editor-model.parameter-source.type-morph-target",
            "cubism.editor-model.parameter-id.class",
            "cubism.editor-model.parameter-id.create",
            "cubism.editor-model.id.class",
            "cubism.editor-model.id.value",
            "cubism.editor-model.parameter-group.class",
            "cubism.editor-model.parameter-group.create",
            "cubism.editor-model.parameter-group-guid.create",
            "cubism.editor-model.parameter-group-id.create",
            "cubism.editor-model.parameter-group.handler",
            "cubism.editor-model.parameter-group.guid",
            "cubism.editor-model.parameter-group.set-name",
            "cubism.editor-model.parameter-group.set-folder-opened",
            "cubism.editor-model.parameter-group.children",
            "cubism.editor-model.parameter-group.parent",
            "cubism.editor-model.parameter-group.id",
            "cubism.editor-model.parameter-group-handler.class",
            "cubism.editor-model.parameter-group-handler.add-parameter-child",
            "cubism.editor-model.parameter-group-handler.add-group-child",
            "cubism.editor-model.parameter-group-handler.remove-descendant",
            "cubism.editor-model.model.parameter-set",
            "cubism.editor-model.parameter-set.class",
            "cubism.editor-model.parameter-set.parameters",
            "cubism.editor-model.simple-undo.create",
            "cubism.editor-model.parameter.class",
            "cubism.editor-model.parameter.source"
        ));
        return Set.copyOf(aliases);
    }

    private EditorParameterStructureSelectorContract() {
    }
}
