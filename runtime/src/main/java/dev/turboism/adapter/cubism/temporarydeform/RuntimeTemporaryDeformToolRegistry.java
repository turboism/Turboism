package dev.turboism.adapter.cubism.temporarydeform;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformTool;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformToolRegistry;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.BoundingBoxOverlayButton;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionAuthority;
import dev.turboism.ui.contribution.EditorUiContributionIdentity;
import dev.turboism.ui.host.EditorUiFamily;
import dev.turboism.ui.host.EdtDispatch;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plugin-generation-scoped {@link TemporaryDeformToolRegistry} enforcing permissions and owning
 * paired overlay button contributions.
 */
public final class RuntimeTemporaryDeformToolRegistry implements TemporaryDeformToolRegistry {

    private final String pluginId;
    private final long generation;
    private final PermissionChecker permissions;
    private final TemporaryDeformCoordinator coordinator;
    private final EditorUiContributionAuthority authority;
    private final Map<String, Registration> registrations = new LinkedHashMap<>();
    private boolean closed;

    public RuntimeTemporaryDeformToolRegistry(
            final String pluginId,
            final long generation,
            final PermissionChecker permissions,
            final TemporaryDeformCoordinator coordinator,
            final EditorUiContributionAuthority authority) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        if (pluginId.isBlank() || generation < 0) {
            throw new IllegalArgumentException("invalid plugin generation");
        }
        this.generation = generation;
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    @Override
    public boolean isAvailable() {
        return EdtDispatch.call("temporary deform registry availability", () -> !closed && coordinator.isAvailable());
    }

    @Override
    public Registration register(final TemporaryDeformTool tool) {
        Objects.requireNonNull(tool, "tool");
        permissions.check(PermissionIds.TURBOISM_CUBISM_MODEL_READ, "temporaryDeformTools.register");
        permissions.check(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE, "temporaryDeformTools.register");
        permissions.check(PermissionIds.TURBOISM_UI_MENU_CONTRIBUTE, "temporaryDeformTools.register");
        final Optional<TemporaryDeformTool.OverlayButtonSpec> overlaySpec = tool.overlayButton();
        if (overlaySpec.isPresent()) {
            permissions.check(PermissionIds.TURBOISM_UI_OVERLAY_CONTRIBUTE, "temporaryDeformTools.register");
        }
        return EdtDispatch.call("temporary deform registry registration", () -> {
            if (closed) {
                throw new IllegalStateException("temporary deform tool registry is closed");
            }
            if (registrations.containsKey(tool.id())) {
                throw new IllegalStateException("temporary deform tool id is already registered: " + tool.id());
            }
            final Registration logic = coordinator.register(pluginId, generation, tool, permissions);
            final Registration overlay;
            if (overlaySpec.isPresent()) {
                try {
                    overlay = authority.contribute(overlayContribution(tool.id(), overlaySpec.get()));
                } catch (RuntimeException | Error failure) {
                    // @containment-exempt: closes logic registration before rethrowing with fatal prioritized
                    Throwable toThrow = failure;
                    try {
                        logic.close();
                    } catch (RuntimeException | Error cleanup) {
                        // @containment-exempt: cleanup failure merged, prioritizing fatal error
                        toThrow = FatalErrors.mergeForCleanup(toThrow, cleanup);
                    }
                    throw FatalErrors.propagate(toThrow);
                }
            } else {
                overlay = () -> {};
            }
            final AtomicBoolean disposed = new AtomicBoolean();
            final Registration owned = new Registration() {
                @Override
                public void close() {
                    if (!disposed.compareAndSet(false, true)) {
                        return;
                    }
                    EdtDispatch.runEventually("temporary deform registry removal", () -> {
                        registrations.remove(tool.id(), this);
                        try {
                            logic.close();
                        } finally {
                            overlay.close();
                        }
                    });
                }
            };
            registrations.put(tool.id(), owned);
            return owned;
        });
    }

    @Override
    public boolean activate(final String toolId) {
        Objects.requireNonNull(toolId, "toolId");
        return coordinator.activate(pluginId, generation, toolId);
    }

    @Override
    public void deactivate() {
        coordinator.deactivate(pluginId, generation);
    }

    @Override
    public Optional<String> activeToolId() {
        return coordinator.activeToolId(pluginId, generation);
    }

    @Override
    public void close() {
        EdtDispatch.runEventually("temporary deform registry cleanup", () -> {
            if (closed) {
                return;
            }
            closed = true;
            RuntimeException first = null;
            for (final Registration registration : new ArrayList<>(registrations.values())) {
                try {
                    registration.close();
                } catch (RuntimeException failure) {
                    if (first == null) {
                        first = failure;
                    } else {
                        first.addSuppressed(failure);
                    }
                }
            }
            registrations.clear();
            if (first != null) {
                throw first;
            }
        });
    }

    private EditorUiContribution<BoundingBoxOverlayButton> overlayContribution(
            final String toolId, final TemporaryDeformTool.OverlayButtonSpec spec) {
        final String buttonId = pluginId + ".temporary-deform." + toolId;
        final BoundingBoxOverlayButton button = new BoundingBoxOverlayButton(
                buttonId,
                spec.tooltip(),
                spec.icons(),
                spec.order(),
                () -> coordinator.activate(pluginId, generation, toolId),
                spec.targetKinds());
        return new EditorUiContribution<>(
                new EditorUiContributionIdentity(pluginId, EditorUiFamily.BOUNDING_BOX_OVERLAY_BUTTON, buttonId),
                spec.order(),
                button);
    }
}
