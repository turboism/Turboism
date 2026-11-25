package dev.turboism.sdk.cubism.temporarydeform;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.plugin.Registration;
import java.util.Objects;
import java.util.Optional;

/**
 * Plugin-generation-owned registry for custom Temporary Deform Tools, obtained through
 * {@code PluginContext.services()}.
 *
 * <p>Registering a tool requires {@code turboism.cubism.model.read},
 * {@code turboism.cubism.model.write}, and {@code turboism.ui.menu.contribute} permissions
 * (plus {@code turboism.ui.overlay.contribute} when {@link TemporaryDeformTool#overlayButton()}
 * is present).</p>
 */
@CubismEditor(from = "5.0.00")
public interface TemporaryDeformToolRegistry extends AutoCloseable {

    /** Registers a custom temporary deform tool for this plugin generation. */
    Registration register(TemporaryDeformTool tool);

    /** Activates one registered tool by id on the current modeling view. */
    boolean activate(String toolId);

    /** Deactivates the currently active custom temporary deform tool owned by this registry. */
    void deactivate();

    /** Returns the id of the active custom temporary deform tool owned by this registry, if any. */
    Optional<String> activeToolId();

    /** Reports whether a verified runtime surface backs this registry. */
    default boolean isAvailable() {
        return true;
    }

    /** Returns the fail-closed sentinel for hosts without verified temporary-deform support. */
    static TemporaryDeformToolRegistry unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Revokes all temporary deform tools owned by this plugin generation. */
    @Override
    void close();

    /** Shared unavailable sentinel. */
    enum Unavailable implements TemporaryDeformToolRegistry {
        INSTANCE;

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public Registration register(final TemporaryDeformTool tool) {
            Objects.requireNonNull(tool, "tool");
            throw new UnsupportedOperationException("temporary deform tool registry is not available");
        }

        @Override
        public boolean activate(final String toolId) {
            Objects.requireNonNull(toolId, "toolId");
            throw new UnsupportedOperationException("temporary deform tool registry is not available");
        }

        @Override
        public void deactivate() {}

        @Override
        public Optional<String> activeToolId() {
            return Optional.empty();
        }

        @Override
        public void close() {}
    }
}
