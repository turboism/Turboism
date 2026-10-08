package dev.turboism.ui.mesh;

import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionIdentity;
import dev.turboism.ui.host.EditorUiFamily;
import java.util.Objects;

/** Immutable exact-generation custom mesh-toolbar tool contribution. */
public record MeshToolbarContributionDescriptor(
        String pluginId,
        long pluginGeneration,
        String toolId,
        String label,
        String iconResourcePath,
        String activeIconResourcePath,
        String rollOverIconResourcePath,
        String selectedIconResourcePath,
        String disabledIconResourcePath,
        String disabledSelectedIconResourcePath,
        int order) {
    public MeshToolbarContributionDescriptor {
        pluginId = text(pluginId, "pluginId");
        if (pluginGeneration < 0) throw new IllegalArgumentException("pluginGeneration must not be negative");
        toolId = text(toolId, "toolId");
        label = text(label, "label");
        iconResourcePath = resource(iconResourcePath, "iconResourcePath");
        activeIconResourcePath = resource(activeIconResourcePath, "activeIconResourcePath");
        rollOverIconResourcePath = resource(rollOverIconResourcePath, "rollOverIconResourcePath");
        selectedIconResourcePath = resource(selectedIconResourcePath, "selectedIconResourcePath");
        disabledIconResourcePath = resource(disabledIconResourcePath, "disabledIconResourcePath");
        disabledSelectedIconResourcePath =
                resource(disabledSelectedIconResourcePath, "disabledSelectedIconResourcePath");
    }

    public MeshToolbarContributionDescriptor(
            String pluginId, long pluginGeneration, String toolId, String label, String iconResourcePath, int order) {
        this(
                pluginId,
                pluginGeneration,
                toolId,
                label,
                iconResourcePath,
                iconResourcePath,
                iconResourcePath,
                iconResourcePath,
                iconResourcePath,
                iconResourcePath,
                order);
    }

    /** Builds the generation-qualified contribution identity for a tool. */
    public static String contributionId(final long generation, final String toolId) {
        if (generation < 0) throw new IllegalArgumentException("generation must not be negative");
        return generation + ":tool:" + text(toolId, "toolId");
    }

    /** Wraps this descriptor as an editor UI contribution. */
    public EditorUiContribution<MeshToolbarContributionDescriptor> contribution() {
        return new EditorUiContribution<>(
                new EditorUiContributionIdentity(
                        pluginId, EditorUiFamily.MESH_TOOLBAR, contributionId(pluginGeneration, toolId)),
                order,
                this);
    }

    /** Validates and extracts a mesh-toolbar tool contribution. */
    public static MeshToolbarContributionDescriptor from(final EditorUiContribution<?> contribution) {
        Objects.requireNonNull(contribution, "contribution");
        if (contribution.identity().family() != EditorUiFamily.MESH_TOOLBAR
                || !(contribution.descriptor() instanceof MeshToolbarContributionDescriptor descriptor)
                || !descriptor.pluginId.equals(contribution.identity().pluginId())
                || !contributionId(descriptor.pluginGeneration, descriptor.toolId)
                        .equals(contribution.identity().contributionId())
                || contribution.order() != descriptor.order) {
            throw new IllegalArgumentException("invalid exact-generation mesh-toolbar tool contribution");
        }
        return descriptor;
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
