package dev.turboism.ui.mesh;

import dev.turboism.ui.contribution.EditorUiContribution;
import dev.turboism.ui.contribution.EditorUiContributionIdentity;
import dev.turboism.ui.host.EditorUiFamily;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/** Exact-generation slider contribution with immutable metadata and contribution-owned value. */
public final class MeshToolbarSliderContributionDescriptor {
    private final String pluginId;
    private final long pluginGeneration;
    private final String controlId;
    private final String label;
    private final int minimum;
    private final int maximum;
    private final AtomicInteger currentValue;
    private final int order;
    private final IntConsumer onChanged;

    public MeshToolbarSliderContributionDescriptor(
            String pluginId,
            long pluginGeneration,
            String controlId,
            String label,
            int minimum,
            int maximum,
            int currentValue,
            int order,
            IntConsumer onChanged) {
        this.pluginId = text(pluginId, "pluginId");
        if (pluginGeneration < 0) throw new IllegalArgumentException("pluginGeneration must not be negative");
        this.pluginGeneration = pluginGeneration;
        this.controlId = text(controlId, "controlId");
        this.label = text(label, "label");
        if (minimum > maximum) throw new IllegalArgumentException("minimum must not exceed maximum");
        if (currentValue < minimum || currentValue > maximum) {
            throw new IllegalArgumentException("currentValue must be within the slider range");
        }
        this.minimum = minimum;
        this.maximum = maximum;
        this.currentValue = new AtomicInteger(currentValue);
        this.order = order;
        this.onChanged = Objects.requireNonNull(onChanged, "onChanged");
    }

    /** Returns the owning plugin ID. */
    public String pluginId() {
        return pluginId;
    }

    /** Returns the exact owning plugin generation. */
    public long pluginGeneration() {
        return pluginGeneration;
    }

    /** Returns the slider ID within its plugin generation. */
    public String controlId() {
        return controlId;
    }

    /** Returns the localized slider label. */
    public String label() {
        return label;
    }

    /** Returns the inclusive lower bound. */
    public int minimum() {
        return minimum;
    }

    /** Returns the inclusive upper bound. */
    public int maximum() {
        return maximum;
    }

    /** Reads runtime-owned state without calling the plugin during toolbar reconciliation. */
    public int currentValue() {
        return currentValue.get();
    }

    /** Returns the toolbar ordering priority. */
    public int order() {
        return order;
    }

    /** Builds the generation-qualified contribution identity for a slider. */
    public static String contributionId(final long generation, final String controlId) {
        if (generation < 0) throw new IllegalArgumentException("generation must not be negative");
        return generation + ":slider:" + text(controlId, "controlId");
    }

    /** Remembers a validated value before the plugin callback can synchronously rebuild the toolbar. */
    public void changed(final int value) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("slider value must be within the declared range");
        }
        currentValue.set(value);
        onChanged.accept(value);
    }

    /** Wraps this descriptor as an editor UI contribution. */
    public EditorUiContribution<MeshToolbarSliderContributionDescriptor> contribution() {
        return new EditorUiContribution<>(
                new EditorUiContributionIdentity(
                        pluginId, EditorUiFamily.MESH_TOOLBAR, contributionId(pluginGeneration, controlId)),
                order,
                this);
    }

    /** Validates and extracts a mesh-toolbar slider contribution. */
    public static MeshToolbarSliderContributionDescriptor from(final EditorUiContribution<?> contribution) {
        Objects.requireNonNull(contribution, "contribution");
        if (contribution.identity().family() != EditorUiFamily.MESH_TOOLBAR
                || !(contribution.descriptor() instanceof MeshToolbarSliderContributionDescriptor descriptor)
                || !descriptor.pluginId.equals(contribution.identity().pluginId())
                || !contributionId(descriptor.pluginGeneration, descriptor.controlId)
                        .equals(contribution.identity().contributionId())
                || contribution.order() != descriptor.order) {
            throw new IllegalArgumentException("invalid exact-generation mesh-toolbar slider contribution");
        }
        return descriptor;
    }

    private static String text(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
