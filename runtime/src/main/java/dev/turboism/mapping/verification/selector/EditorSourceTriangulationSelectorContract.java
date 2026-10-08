package dev.turboism.mapping.verification.selector;

import java.util.Set;

/** Detached GEditableMesh2 observation, verified against the exact 5.3.02 artifact. */
public final class EditorSourceTriangulationSelectorContract {
    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = "cubism.editor-model.source-triangulation.read";
    public static final Set<String> NEW_ALIASES = Set.of(
        "cubism.editor-model.editable-mesh.class",
        "cubism.editor-model.editable-mesh.create",
        "cubism.editor-model.editable-mesh.gl-positions",
        "cubism.editor-model.editable-mesh.gl-indices-default"
    );
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.editable-mesh.class",
        "cubism.editor-model.editable-mesh.create",
        "cubism.editor-model.editable-mesh.gl-positions",
        "cubism.editor-model.editable-mesh.gl-indices-default",
        "cubism.editor-model.editable-mesh.init-by-indices",
        "cubism.editor-model.coord-type.canvas"
    );
    private EditorSourceTriangulationSelectorContract() { }
}
