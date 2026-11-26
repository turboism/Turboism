package dev.turboism.ui.mesh;

import dev.turboism.sdk.plugin.Registration;
import java.util.Optional;

/**
 * Toolbar-facing coordinator contract for custom mesh tools.
 *
 * <p>Decouples {@code dev.turboism.ui.mesh} from {@code dev.turboism.adapter.cubism.mesh} so
 * toolbar providers reconcile selection state without importing Cubism adapters.</p>
 */
public interface MeshToolToolbarCoordinator {

    /** Records the active host generation for toolbar activation checks. */
    void replaceHostGeneration(long hostGeneration);

    /** Registers a listener invoked whenever active tool state changes. */
    Registration onStateChanged(Runnable listener);

    /** Activates the tool owned by the supplied plugin generation. */
    void activate(String pluginId, long pluginGeneration, String toolId);

    /** Deactivates the tool owned by the supplied plugin generation. */
    void deactivate(String pluginId, long pluginGeneration, String toolId);

    /** Reports whether an exact mesh-editor session is currently open. */
    boolean hasActiveSession();

    /** Returns the identity of the active custom tool, if any. */
    Optional<ActiveToolIdentity> activeToolIdentity();

    /** Clears any active custom tool when the user switches to a native host tool. */
    void nativeToolActivated();

    /** Identifies the currently active custom mesh tool without exposing adapter types. */
    record ActiveToolIdentity(String pluginId, long generation, String toolId) {}
}
