package dev.turboism.plugin.frameanimation.domain;

import java.util.List;
import java.util.Objects;

/**
 * Validated execution plan for importing a sequence of animation frames into a Cubism model.
 */
public record FrameAnimationImportPlan(
        String partName,
        List<FrameItem> frames,
        ParameterConfig parameterConfig,
        DisplayStrategy displayStrategy,
        KeyformBindingMode bindingMode,
        AnchorPoint anchor,
        float centerX,
        float centerY,
        int drawOrder,
        boolean flipVertical) {

    public FrameAnimationImportPlan(
            final String partName,
            final List<FrameItem> frames,
            final ParameterConfig parameterConfig,
            final DisplayStrategy displayStrategy,
            final KeyformBindingMode bindingMode,
            final AnchorPoint anchor,
            final float centerX,
            final float centerY,
            final int drawOrder) {
        this(
                partName,
                frames,
                parameterConfig,
                displayStrategy,
                bindingMode,
                anchor,
                centerX,
                centerY,
                drawOrder,
                false);
    }

    public FrameAnimationImportPlan {
        partName = Objects.requireNonNull(partName, "partName").strip();
        if (partName.isEmpty()) {
            throw new IllegalArgumentException("partName must not be blank");
        }
        frames = List.copyOf(Objects.requireNonNull(frames, "frames"));
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("frames must not be empty");
        }
        Objects.requireNonNull(parameterConfig, "parameterConfig");
        Objects.requireNonNull(displayStrategy, "displayStrategy");
        Objects.requireNonNull(bindingMode, "bindingMode");
        Objects.requireNonNull(anchor, "anchor");
        if (drawOrder < 0 || drawOrder > 1000) {
            throw new IllegalArgumentException("drawOrder must be between 0 and 1000: " + drawOrder);
        }
    }
}
