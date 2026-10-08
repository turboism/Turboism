package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.Incubating;

/**
 * Plugin-defined integer slider in the native mesh toolbar.
 *
 * <p>The runtime validates bounds and values before publication and callback delivery. Callbacks
 * execute on the EDT without runtime locks held.</p>
 *
 * <p><strong>Preview API:</strong> this contract may evolve before stabilization.</p>
 */
@Incubating
public interface MeshToolbarSlider {
    /** Returns the plugin-generation-local control identifier. */
    String id();

    /** Returns the user-visible control label. */
    String label();

    /** Returns the inclusive minimum value. */
    int minimum();

    /** Returns the inclusive maximum value. */
    int maximum();

    /** Returns the current value used when materializing or rebuilding the host control. */
    int value();

    /**
     * Accepts a host-originated value change on the EDT.
     *
     * @param value validated value within {@link #minimum()} and {@link #maximum()}
     */
    void setValue(int value);

    /** Returns the deterministic toolbar ordering key. */
    default int order() {
        return 0;
    }
}
