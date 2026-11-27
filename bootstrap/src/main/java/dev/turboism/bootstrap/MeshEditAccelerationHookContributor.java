package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.meshedit.MeshEditAccelerationTransformer;
import dev.turboism.config.optimization.OptimizationCatalog;
import java.lang.instrument.ClassFileTransformer;

/** Installs editable-mesh and ArtMesh edit-mode acceleration before Cubism loads mesh classes. */
final class MeshEditAccelerationHookContributor extends AbstractAccelerationHookContributor {

    static final String HOOK_POLICY_ID = "cubism.mesh.edit-acceleration";
    private static final String ID = "TURBOISM_MESH_EDIT_ACCELERATION";

    MeshEditAccelerationHookContributor() {
        super(ID, HOOK_POLICY_ID, OptimizationCatalog.MESH_EDIT_ACCELERATION);
    }

    @Override
    protected ClassFileTransformer createTransformer() {
        return new MeshEditAccelerationTransformer();
    }
}
