package dev.turboism.ui.toolbar;

import dev.turboism.sdk.cubism.modeling.ModelingTool;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionIdentity;
import dev.turboism.ui.host.EditorUiFamily;
import java.util.List;
import java.util.Objects;

/** Exact plugin-generation ordinary tool contribution; independent of action buttons. */
public record ModelingToolbarContributionDescriptor(
        String pluginId,
        long pluginGeneration,
        String toolId,
        String label,
        List<String> icons,
        MainToolbarRegistry.Placement placement,
        int order) {
    public ModelingToolbarContributionDescriptor {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(toolId, "toolId");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(placement, "placement");
        if (pluginGeneration < 0 || pluginId.isBlank() || toolId.isBlank() || label.isBlank()) {
            throw new IllegalArgumentException("ordinary tool identity and label must be nonempty");
        }
        icons = List.copyOf(icons);
        if (icons.size() != 6) throw new IllegalArgumentException("ordinary tools require six state icons");
        for (String path : icons) {
            if (path.isBlank() || path.startsWith("/") || path.contains("..") || path.contains("\\")) {
                throw new IllegalArgumentException("ordinary tool icon must be a normalized plugin resource path");
            }
        }
    }

    /** Freezes a tool's metadata and six resource paths for one plugin generation. */
    public static ModelingToolbarContributionDescriptor of(
            String pluginId, long generation, ModelingTool tool, MainToolbarRegistry.Placement placement) {
        return new ModelingToolbarContributionDescriptor(
                pluginId,
                generation,
                tool.id(),
                tool.label(),
                List.of(
                        tool.iconResourcePath(),
                        tool.activeIconResourcePath(),
                        tool.rollOverIconResourcePath(),
                        tool.selectedIconResourcePath(),
                        tool.disabledIconResourcePath(),
                        tool.disabledSelectedIconResourcePath()),
                placement,
                tool.order());
    }

    /** Produces the exact-generation main-toolbar authority contribution. */
    public EditorUiContribution<ModelingToolbarContributionDescriptor> contribution() {
        return new EditorUiContribution<>(
                new EditorUiContributionIdentity(pluginId, EditorUiFamily.MAIN_TOOLBAR, contributionId()), order, this);
    }

    private String contributionId() {
        return pluginGeneration + ":modeling-tool:" + toolId;
    }

    /** Validates descriptor identity and family before a provider materializes native widgets. */
    public static ModelingToolbarContributionDescriptor from(EditorUiContribution<?> contribution) {
        if (!(contribution.descriptor() instanceof ModelingToolbarContributionDescriptor descriptor)
                || contribution.identity().family() != EditorUiFamily.MAIN_TOOLBAR
                || !descriptor.pluginId.equals(contribution.identity().pluginId())
                || !descriptor.contributionId().equals(contribution.identity().contributionId())
                || contribution.order() != descriptor.order) {
            throw new IllegalArgumentException("invalid exact-generation modeling tool contribution");
        }
        return descriptor;
    }
}
