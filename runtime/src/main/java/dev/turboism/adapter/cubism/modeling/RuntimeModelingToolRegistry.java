package dev.turboism.adapter.cubism.modeling;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.modeling.ModelingTool;
import dev.turboism.sdk.cubism.modeling.ModelingToolRegistry;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.ui.contribution.EditorUiContributionAuthority;
import dev.turboism.ui.host.EdtDispatch;
import dev.turboism.ui.toolbar.ModelingToolbarContributionDescriptor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Plugin-generation ownership for the ordinary modeling service. No lock crosses the EDT. */
public final class RuntimeModelingToolRegistry implements ModelingToolRegistry {
    public static final String REQUIRED_CAPABILITY = "cubism.modeling.custom-tools";
    private final String pluginId;
    private final long generation;
    private final PermissionChecker permissions;
    private final boolean granted;
    private final ModelingToolCoordinator coordinator;
    private final EditorUiContributionAuthority authority;
    private final Map<String, Registration> tools = new LinkedHashMap<>();
    private boolean closed;

    public RuntimeModelingToolRegistry(
            String pluginId,
            long generation,
            PermissionChecker permissions,
            boolean granted,
            ModelingToolCoordinator coordinator,
            EditorUiContributionAuthority authority) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        if (pluginId.isBlank() || generation < 0) throw new IllegalArgumentException("invalid plugin generation");
        this.generation = generation;
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.granted = granted;
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    @Override
    public boolean isAvailable() {
        return EdtDispatch.call(
                "modeling registry availability", () -> !closed && granted && coordinator.isAvailable());
    }

    @Override
    public Registration register(ModelingTool tool, MainToolbarRegistry.Placement placement) {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(placement, "placement");
        if (!granted)
            throw new UnsupportedOperationException("plugin capability " + REQUIRED_CAPABILITY + " is required");
        permissions.check(PermissionIds.TURBOISM_UI_TOOLBAR_MAIN_CONTRIBUTE, "modelingTools.register");
        final var descriptor = ModelingToolbarContributionDescriptor.of(pluginId, generation, tool, placement);
        return EdtDispatch.call("modeling registry registration", () -> {
            if (closed) throw new IllegalStateException("modeling registry is closed");
            if (tools.containsKey(descriptor.toolId()))
                throw new IllegalStateException("modeling tool id is already registered");
            final Registration logic = coordinator.register(pluginId, generation, tool, permissions);
            final Registration ui;
            try {
                ui = authority.contribute(descriptor.contribution());
            } catch (RuntimeException | Error failure) {
                try {
                    logic.close();
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
            final AtomicBoolean disposed = new AtomicBoolean();
            final Registration owned = new Registration() {
                @Override
                public void close() {
                    if (!disposed.compareAndSet(false, true)) return;
                    EdtDispatch.call("modeling registry removal", () -> {
                        tools.remove(descriptor.toolId(), this);
                        try {
                            logic.close();
                        } finally {
                            ui.close();
                        }
                        return null;
                    });
                }
            };
            tools.put(descriptor.toolId(), owned);
            return owned;
        });
    }

    @Override
    public void close() {
        EdtDispatch.call("modeling registry cleanup", () -> {
            if (closed) return null;
            closed = true;
            RuntimeException first = null;
            for (Registration registration : new ArrayList<>(tools.values())) {
                try {
                    registration.close();
                } catch (RuntimeException failure) {
                    if (first == null) first = failure;
                    else first.addSuppressed(failure);
                }
            }
            tools.clear();
            if (first != null) throw first;
            return null;
        });
    }
}
