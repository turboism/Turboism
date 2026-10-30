package dev.turboism.plugin.geometricmeshlayout;

import dev.turboism.sdk.cubism.mesh.MeshBrush;
import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.MeshToolContext;
import dev.turboism.sdk.cubism.mesh.MeshToolRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import java.util.Objects;

/**
 * Official SDK-only Geometric Mesh Layout plugin for mesh-editing mode.
 *
 * <p>Registers a mesh-toolbar tool that lets the operator place a center anchor on the canvas and
 * generate circular or equilateral-triangle vertices and edges through a configuration dialog.</p>
 */
public final class GeometricMeshLayoutPlugin implements TurboismPlugin {
    private final Object monitor = new Object();
    private PluginContext context;
    private MeshBrush activeHandle;
    private boolean enabled;

    private final MeshTool tool = new MeshTool() {
        @Override
        public String id() {
            return "geometric-mesh-layout";
        }

        @Override
        public String label() {
            return labelText("mesh.geometric-layout.label", "Geometric Mesh Layout");
        }

        @Override
        public String iconResourcePath() {
            return "icons/geometric-mesh-layout.png";
        }

        @Override
        public String activeIconResourcePath() {
            return "icons/geometric-mesh-layout-active.png";
        }

        @Override
        public String rollOverIconResourcePath() {
            return "icons/geometric-mesh-layout-rollover.png";
        }

        @Override
        public String selectedIconResourcePath() {
            return "icons/geometric-mesh-layout-selected.png";
        }

        @Override
        public String disabledIconResourcePath() {
            return "icons/geometric-mesh-layout-disabled.png";
        }

        @Override
        public String disabledSelectedIconResourcePath() {
            return "icons/geometric-mesh-layout-disabled-selected.png";
        }

        @Override
        public int order() {
            return 120;
        }

        @Override
        public void activate(final MeshToolContext toolContext) {
            final MeshBrush candidate = Objects.requireNonNull(
                    Objects.requireNonNull(toolContext, "toolContext").selectionBrush(), "selectionBrush");
            final MeshBrush previous;
            synchronized (monitor) {
                previous = activeHandle;
                activeHandle = candidate;
            }
            if (previous != null) {
                closeQuietly(previous);
            }
        }

        @Override
        public void deactivate() {
            closeActiveHandle();
        }
    };

    @Override
    public void init(final PluginContext context) {
        synchronized (monitor) {
            if (this.context != null) {
                throw new IllegalStateException("Geometric Mesh Layout is already initialized");
            }
            this.context = Objects.requireNonNull(context, "context");
        }
    }

    @Override
    public void enable() {
        final PluginContext current;
        synchronized (monitor) {
            if (enabled) return;
            current = Objects.requireNonNull(context, "Geometric Mesh Layout is not initialized");
        }
        Registration toolRegistration = null;
        try {
            final MeshToolRegistry registry = current.services().require(MeshToolRegistry.class);
            toolRegistration = registry.register(tool);
            current.disposableScope().register(toolRegistration);
            synchronized (monitor) {
                enabled = true;
            }
        } catch (RuntimeException | Error failure) {
            closeSuppressing(toolRegistration, failure);
            throw failure;
        }
    }

    @Override
    public void disable() {
        closeActiveHandle();
    }

    @Override
    public void shutdown() {
        closeActiveHandle();
    }

    private String labelText(final String key, final String fallback) {
        final PluginContext current;
        synchronized (monitor) {
            current = context;
        }
        if (current == null) return fallback;
        return current.localization().text(key, fallback);
    }

    private void closeActiveHandle() {
        final MeshBrush handle;
        synchronized (monitor) {
            handle = activeHandle;
            activeHandle = null;
        }
        if (handle != null) {
            closeQuietly(handle);
        }
    }

    private static void closeQuietly(final MeshBrush handle) {
        try {
            handle.close();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("failed to close geometric mesh layout handle", failure);
        }
    }

    private static void closeSuppressing(final Registration registration, final Throwable failure) {
        if (registration == null) return;
        try {
            registration.close();
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }
}
