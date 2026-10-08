package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.Incubating;
import dev.turboism.sdk.plugin.Registration;

/**
 * Plugin-generation-scoped registry for native mesh-toolbar contributions.
 *
 * <p>Registration requires capability {@code cubism.mesh.custom-tools} and permission
 * {@code turboism.ui.toolbar.mesh.contribute}. Returned registrations revoke only their exact
 * contribution generation and are idempotent.</p>
 *
 * <p><strong>Preview API:</strong> this contract may evolve before stabilization.</p>
 */
@Incubating
public interface MeshToolRegistry extends AutoCloseable {
    /**
     * Registers one custom mesh tool for this plugin generation.
     *
     * @param tool SDK-only tool contribution
     * @return idempotent exact-generation revocation
     */
    Registration register(MeshTool tool);

    /**
     * Contributes one integer control to the native mesh toolbar.
     *
     * @param slider SDK-only slider contribution
     * @return idempotent exact-generation revocation
     */
    Registration contributeSlider(MeshToolbarSlider slider);

    /** Revokes every tool and slider still owned by this registry. */
    @Override
    void close();
}
