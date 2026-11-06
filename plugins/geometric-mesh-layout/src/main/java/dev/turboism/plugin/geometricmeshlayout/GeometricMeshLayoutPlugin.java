package dev.turboism.plugin.geometricmeshlayout;

import dev.turboism.sdk.cubism.mesh.MeshToolRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import java.util.Objects;

/**
 * Official SDK-only Geometric Mesh Layout plugin for mesh-editing mode.
 *
 * <p>Registers a mesh-toolbar tool that lets the operator place a center anchor on the canvas and
 * generate circular or regular-polygon vertices and edges through a configuration dialog. The
 * tool's geometry, parameter state and dialog live in this module; the canvas overlay, projection
 * and the single-Undo commit come from the runtime mesh canvas session.</p>
 */
public final class GeometricMeshLayoutPlugin implements TurboismPlugin {
    private final Object monitor = new Object();
    private PluginContext context;
    private GeometricMeshLayoutTool activeTool;
    private boolean enabled;

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
        final GeometricMeshLayoutTool tool = new GeometricMeshLayoutTool(current);
        try {
            final MeshToolRegistry registry = current.services().require(MeshToolRegistry.class);
            toolRegistration = registry.register(tool);
            current.disposableScope().register(toolRegistration);
            synchronized (monitor) {
                activeTool = tool;
                enabled = true;
            }
        } catch (RuntimeException | Error failure) {
            tool.close();
            closeSuppressing(toolRegistration, failure);
            throw failure;
        }
    }

    @Override
    public void disable() {
        final GeometricMeshLayoutTool tool;
        synchronized (monitor) {
            tool = activeTool;
        }
        if (tool != null) tool.deactivateActivation();
    }

    @Override
    public void shutdown() {
        final GeometricMeshLayoutTool tool;
        synchronized (monitor) {
            tool = activeTool;
            activeTool = null;
        }
        if (tool != null) tool.close();
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
