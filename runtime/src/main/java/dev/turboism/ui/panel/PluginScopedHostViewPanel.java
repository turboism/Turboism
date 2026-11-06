package dev.turboism.ui.panel;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.ui.HostViewPanel;
import dev.turboism.sdk.ui.HostViewPanelHandle;
import dev.turboism.sdk.ui.PanelView;
import java.util.Objects;

/**
 * Plugin-facing {@link HostViewPanel}: the permission-gated, generation-scoped view of the
 * host-session-owned {@link RuntimeHostViewPanelRegistry}.
 *
 * <p>Two invariants live here rather than in the registry, because they are per plugin: attaching
 * demands {@link PermissionIds#TURBOISM_UI_PANEL_CONTRIBUTE}, and every handle is adopted into the
 * plugin's {@link DisposableScope} so the generation fence closes the panel instead of leaving a
 * retired plugin's content on the host view. A late {@link HostViewPanelHandle#setContent} after
 * that fence is dropped silently by the closed handle.</p>
 */
public final class PluginScopedHostViewPanel implements HostViewPanel {

    /** Operation identity reported by the permission gate for an attachment request. */
    public static final String ATTACH_OPERATION = "ui.host-view-panel.contribute";

    private final RuntimeHostViewPanelRegistry registry;
    private final PermissionChecker permissionChecker;
    private final DisposableScope scope;

    /**
     * @param registry the host-session-owned anchor registry, never {@code null}
     * @param permissionChecker the plugin's checker, never {@code null}
     * @param scope the plugin's disposable scope that owns the attached handles, never {@code null}
     */
    public PluginScopedHostViewPanel(
            final RuntimeHostViewPanelRegistry registry,
            final PermissionChecker permissionChecker,
            final DisposableScope scope) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    @Override
    public HostViewPanelHandle attach(final String anchor, final PanelView content, final int order) {
        permissionChecker.check(PermissionIds.TURBOISM_UI_PANEL_CONTRIBUTE, ATTACH_OPERATION);
        final HostViewPanelHandle handle = registry.attach(anchor, content, order);
        try {
            scope.register(handle);
        } catch (RuntimeException fenced) {
            // The plugin generation is already fenced: close the freshly attached panel rather
            // than leaving content on a host view no live generation owns.
            handle.close();
            throw fenced;
        }
        return handle;
    }

    @Override
    public boolean isAvailable() {
        return registry.isAvailable() && !scope.isSealed();
    }
}
