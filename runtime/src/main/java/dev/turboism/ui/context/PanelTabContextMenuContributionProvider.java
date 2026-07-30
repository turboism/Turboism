package dev.turboism.ui.context;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionProvider;
import dev.turboism.ui.contribution.EditorUiProviderAdmission;
import dev.turboism.ui.host.EditorUiFamily;
import dev.turboism.ui.panel.PanelTabMenuCoordinator;

import java.util.List;
import java.util.Objects;

/** Admits panel-tab context-menu policy without exposing native tab objects to plugins. */
public final class PanelTabContextMenuContributionProvider implements EditorUiContributionProvider {

    private final EditorUiProviderAdmission admission;
    private final PanelTabMenuCoordinator coordinator;

    public PanelTabContextMenuContributionProvider(
        final EditorUiProviderAdmission admission,
        final PanelTabMenuCoordinator coordinator
    ) {
        this.admission = Objects.requireNonNull(admission, "admission");
        if (admission.family() != EditorUiFamily.CONTEXT_MENU) {
            throw new IllegalArgumentException("panel-tab menu provider requires CONTEXT_MENU admission");
        }
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public EditorUiFamily family() {
        return EditorUiFamily.CONTEXT_MENU;
    }

    @Override
    public EditorUiProviderAdmission admission() {
        return admission;
    }

    @Override
    public Registration apply(
        final long hostGeneration,
        final List<EditorUiContribution<?>> contributions
    ) {
        if (!admission.isAdmittedTo(hostGeneration)) {
            throw new IllegalStateException("panel-tab menu provider admission is stale");
        }
        coordinator.update(contributions.stream()
            .map(EditorUiContribution::descriptor)
            .filter(ContextMenuRegistry.ContextMenuContribution.class::isInstance)
            .map(ContextMenuRegistry.ContextMenuContribution.class::cast)
            .filter(value -> value.target() == ContextMenuRegistry.Target.PANEL_TAB)
            .toList());
        return () -> coordinator.update(List.of());
    }
}
