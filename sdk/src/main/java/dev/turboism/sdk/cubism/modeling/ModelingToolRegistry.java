package dev.turboism.sdk.cubism.modeling;

import dev.turboism.sdk.Incubating;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import java.util.Objects;

/**
 * Plugin-generation-owned ordinary modeling tool service, obtained through PluginContext.services().
 * Requires cubism.modeling.custom-tools and turboism.ui.toolbar.main.contribute. Closing a
 * registration revokes only its exact generation; closing the service revokes all owned tools.
 */
@Incubating
public interface ModelingToolRegistry extends AutoCloseable {
    /** Registers a tool at a semantic main-toolbar placement. */
    Registration register(ModelingTool tool, MainToolbarRegistry.Placement placement);
    /** Reports whether a verified runtime service backs this instance. */
    default boolean isAvailable() {
        return true;
    }
    /** Fail-closed sentinel for hosts without this independently verified feature. */
    static ModelingToolRegistry unavailable() {
        return Unavailable.INSTANCE;
    }
    /** Revokes all tools owned by this plugin generation. */
    @Override
    void close();

    /** Shared unavailable service. */
    enum Unavailable implements ModelingToolRegistry {
        INSTANCE;

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public Registration register(ModelingTool tool, MainToolbarRegistry.Placement placement) {
            Objects.requireNonNull(tool, "tool");
            Objects.requireNonNull(placement, "placement");
            throw new UnsupportedOperationException("modeling tool registry is not available");
        }

        @Override
        public void close() {}
    }
}
