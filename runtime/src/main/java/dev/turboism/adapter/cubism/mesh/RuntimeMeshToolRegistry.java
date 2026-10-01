package dev.turboism.adapter.cubism.mesh;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.MeshToolRegistry;
import dev.turboism.sdk.cubism.mesh.MeshToolbarSlider;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.ui.contribution.EditorUiContributionAuthority;
import dev.turboism.ui.mesh.MeshToolbarContributionDescriptor;
import dev.turboism.ui.mesh.MeshToolbarSliderContributionDescriptor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Plugin-generation-owned SDK mesh-tool registry. */
public final class RuntimeMeshToolRegistry implements MeshToolRegistry {
    public static final String REQUIRED_CAPABILITY = "cubism.mesh.custom-tools";

    private final String pluginId;
    private final long pluginGeneration;
    private final PermissionChecker permissions;
    private final boolean capabilityGranted;
    private final MeshToolCoordinator coordinator;
    private final EditorUiContributionAuthority authority;
    private final Object monitor = new Object();
    private final Map<String, Registration> tools = new HashMap<>();
    private final Map<String, Registration> sliders = new HashMap<>();
    private boolean closed;

    public RuntimeMeshToolRegistry(
            String pluginId,
            long pluginGeneration,
            PermissionChecker permissions,
            boolean capabilityGranted,
            MeshToolCoordinator coordinator,
            EditorUiContributionAuthority authority) {
        this.pluginId = text(pluginId, "pluginId");
        if (pluginGeneration < 0) throw new IllegalArgumentException("pluginGeneration must not be negative");
        this.pluginGeneration = pluginGeneration;
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.capabilityGranted = capabilityGranted;
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    /** Returns the exact plugin generation owned by this registry. */
    public long pluginGeneration() {
        return pluginGeneration;
    }

    /** Binds registry cleanup to the plugin's disposable scope. */
    public void bind(final DisposableScope scope) {
        Objects.requireNonNull(scope, "scope").register(this);
    }

    @Override
    public Registration register(final MeshTool tool) {
        Objects.requireNonNull(tool, "tool");
        requireAuthorized("meshTools.register");
        final MeshToolbarContributionDescriptor descriptor = new MeshToolbarContributionDescriptor(
                pluginId,
                pluginGeneration,
                text(tool.id(), "tool.id"),
                text(tool.label(), "tool.label"),
                resource(tool.iconResourcePath(), "tool.iconResourcePath"),
                resource(tool.activeIconResourcePath(), "tool.activeIconResourcePath"),
                resource(tool.rollOverIconResourcePath(), "tool.rollOverIconResourcePath"),
                resource(tool.selectedIconResourcePath(), "tool.selectedIconResourcePath"),
                resource(tool.disabledIconResourcePath(), "tool.disabledIconResourcePath"),
                resource(tool.disabledSelectedIconResourcePath(), "tool.disabledSelectedIconResourcePath"),
                tool.order());
        synchronized (monitor) {
            requireOpen();
            if (tools.containsKey(descriptor.toolId())) {
                throw new IllegalStateException("mesh tool id is already registered by this plugin generation");
            }
            final Registration coordinatorRegistration =
                    coordinator.register(pluginId, pluginGeneration, tool, permissions);
            final Registration uiRegistration;
            try {
                uiRegistration = authority.contribute(descriptor.contribution());
            } catch (RuntimeException | Error failure) {
                closeSuppressing(coordinatorRegistration, failure);
                throw failure;
            }
            final Registration owned =
                    owned(tools, descriptor.toolId(), List.of(uiRegistration, coordinatorRegistration));
            tools.put(descriptor.toolId(), owned);
            return owned;
        }
    }

    @Override
    public Registration contributeSlider(final MeshToolbarSlider slider) {
        Objects.requireNonNull(slider, "slider");
        requireAuthorized("meshTools.contributeSlider");
        final int minimum = slider.minimum();
        final int maximum = slider.maximum();
        final int current = slider.value();
        final MeshToolbarSliderContributionDescriptor descriptor = new MeshToolbarSliderContributionDescriptor(
                pluginId,
                pluginGeneration,
                text(slider.id(), "slider.id"),
                text(slider.label(), "slider.label"),
                minimum,
                maximum,
                current,
                slider.order(),
                slider::setValue);
        synchronized (monitor) {
            requireOpen();
            if (sliders.containsKey(descriptor.controlId())) {
                throw new IllegalStateException("mesh slider id is already registered by this plugin generation");
            }
            final Registration ui = authority.contribute(descriptor.contribution());
            final Registration owned = owned(sliders, descriptor.controlId(), List.of(ui));
            sliders.put(descriptor.controlId(), owned);
            return owned;
        }
    }

    private Registration owned(
            final Map<String, Registration> owners, final String id, final List<Registration> registrations) {
        return new Registration() {
            private final AtomicBoolean registrationClosed = new AtomicBoolean();

            @Override
            public void close() {
                if (!registrationClosed.compareAndSet(false, true)) return;
                synchronized (monitor) {
                    owners.remove(id, this);
                }
                RuntimeException first = closeAll(registrations);
                if (first != null) throw first;
            }
        };
    }

    private void requireAuthorized(final String operation) {
        if (!capabilityGranted) {
            throw new UnsupportedOperationException("plugin capability " + REQUIRED_CAPABILITY + " is required");
        }
        permissions.check(PermissionIds.TURBOISM_UI_TOOLBAR_MESH_CONTRIBUTE, operation);
    }

    @Override
    public void close() {
        final List<Registration> ownedSliders;
        final List<Registration> ownedTools;
        synchronized (monitor) {
            if (closed) return;
            closed = true;
            ownedSliders = new ArrayList<>(sliders.values());
            ownedTools = new ArrayList<>(tools.values());
            sliders.clear();
            tools.clear();
        }
        // Closers may synchronously cross to EDT and reenter the registry. Never hold its monitor.
        RuntimeException first = closeAll(ownedSliders);
        first = append(first, closeAll(ownedTools));
        try {
            coordinator.removePlugin(pluginId, pluginGeneration);
        } catch (RuntimeException failure) {
            first = append(first, failure);
        }
        if (first != null) throw first;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("mesh tool registry is closed");
    }

    private static RuntimeException closeAll(final List<Registration> values) {
        RuntimeException first = null;
        for (int index = values.size() - 1; index >= 0; index--) {
            try {
                values.get(index).close();
            } catch (RuntimeException failure) {
                first = append(first, failure);
            }
        }
        return first;
    }

    private static RuntimeException append(final RuntimeException first, final RuntimeException next) {
        if (next == null) return first;
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }

    private static void closeSuppressing(final Registration registration, final Throwable failure) {
        try {
            registration.close();
        } catch (RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    private static String resource(final String value, final String name) {
        final String path = text(value, name);
        if (path.startsWith("/") || path.contains("..") || path.contains("\\")) {
            throw new IllegalArgumentException(name + " must be a normalized plugin resource path");
        }
        return path;
    }

    private static String text(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
