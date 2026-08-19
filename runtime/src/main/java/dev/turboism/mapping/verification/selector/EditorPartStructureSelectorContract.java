package dev.turboism.mapping.verification.selector;

import java.util.HashSet;
import java.util.Set;

/** Exact additive selector contract for Editor Part collection structure writes (add / copy / delete). */
public final class EditorPartStructureSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = "cubism.editor-model.part-structure.write";

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
            "cubism.editor-model.complete-pack.update-part-palette",
            "cubism.editor-model.complete-pack.repaint-canvas",
            "cubism.editor-model.model-source.parts",
            "cubism.editor-model.model.parts",
            "cubism.editor-model.model-source.current-instance",
            "cubism.editor-model.model-source.root-part",
            "cubism.editor-model.model-source.handler",
            "cubism.editor-model.model-handler.class",
            "cubism.editor-model.model-handler.create-free-id-default",
            "cubism.editor-model.model-handler.remove-objects",
            "cubism.editor-model.copy-helper.copy",
            "cubism.editor-model.part.class",
            "cubism.editor-model.part.source",
            "cubism.editor-model.part-source.class",
            "cubism.editor-model.part-source.create",
            "cubism.editor-model.part-source.id",
            "cubism.editor-model.part-source.set-id",
            "cubism.editor-model.part-source.set-guid",
            "cubism.editor-model.part-source.set-local-name",
            "cubism.editor-model.part-source.set-default-order",
            "cubism.editor-model.part-source.children",
            "cubism.editor-model.part-source.parent",
            "cubism.editor-model.part-source.handler",
            "cubism.editor-model.part-handler.class",
            "cubism.editor-model.part-handler.add-part-child",
            "cubism.editor-model.part-id.class",
            "cubism.editor-model.part-id.create",
            "cubism.editor-model.part-id.value",
            "cubism.editor-model.part-guid.create",
            "cubism.editor-model.id.class",
            "cubism.editor-model.id.value"
        ));
        return Set.copyOf(aliases);
    }

    private EditorPartStructureSelectorContract() {
    }
}
