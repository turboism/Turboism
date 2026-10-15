package dev.turboism.plugin.frameanimation.service;

import dev.turboism.plugin.frameanimation.domain.DisplayStrategy;
import dev.turboism.plugin.frameanimation.domain.KeyformBindingMode;
import dev.turboism.plugin.frameanimation.domain.ParameterConfig;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Creates parameter definitions and binds frame ArtMeshes to parameter keyforms with opacity values.
 */
public final class FrameKeyformBinder {

    private static final float EPSILON = 0.01f;

    private FrameKeyformBinder() {}

    /**
     * Resolves or creates the Cubism parameter according to {@code config}.
     */
    public static Parameter getOrCreateParameter(final CubismModel model, final ParameterConfig config) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(config, "config");

        return model.parameters().findById(config.id()).orElseGet(() -> {
            return model.parameters().create(config.toDefinition());
        });
    }

    /**
     * Binds each frame mesh to the parameter and sets the keyform opacities according to the strategy.
     *
     * @param model active Cubism model
     * @param parameter target parameter
     * @param meshes ordered list of frame drawables
     * @param strategy step flipbook or smooth crossfade
     * @param bindingMode local neighborhood or full range
     */
    public static void bindFrameOpacities(
            final CubismModel model,
            final Parameter parameter,
            final List<Drawable> meshes,
            final DisplayStrategy strategy,
            final KeyformBindingMode bindingMode) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(parameter, "parameter");
        Objects.requireNonNull(meshes, "meshes");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(bindingMode, "bindingMode");

        final int count = meshes.size();
        if (count == 0) return;

        final ParameterId paramId = parameter.id();
        final TreeSet<Float> allKeyValues = new TreeSet<>();
        final Map<Integer, Map<Float, Float>> meshKeyOpacityMap = new HashMap<>();

        final float minVal = parameter.getMinimumValue();
        final float maxVal = parameter.getMaximumValue();
        final float defVal = parameter.getDefaultValue();
        final boolean isRepeat = parameter.repeat().orElse(false);

        // 1. Calculate key values and intended opacities for each mesh
        for (int i = 0; i < count; i++) {
            final Map<Float, Float> opacities =
                    calculateOpacitiesForMesh(i, count, minVal, maxVal, defVal, isRepeat, strategy, bindingMode);
            meshKeyOpacityMap.put(i, opacities);
            allKeyValues.addAll(opacities.keySet());

            // Build binding points in strictly sorted unique order
            final List<ParameterBindingPoint> points = new ArrayList<>(opacities.size());
            int pointIdx = 0;
            for (final float val : new TreeSet<>(opacities.keySet())) {
                points.add(new ParameterBindingPoint(
                        new ParameterBindingPointId("PT_" + meshes.get(i).id().value() + "_" + (pointIdx++)), val));
            }

            // Bind mesh to parameter
            model.parameterBindings(paramId)
                    .bind(ParameterBindingTarget.artMesh(meshes.get(i).id()), points);
        }

        // 2. Iterate across all unique key positions and set opacity for active keyforms
        for (final float keyVal : allKeyValues) {
            parameter.setValue(keyVal);
            model.update();

            for (int i = 0; i < count; i++) {
                final Map<Float, Float> opacities = meshKeyOpacityMap.get(i);
                if (opacities.containsKey(keyVal)) {
                    final float targetOpacity = opacities.get(keyVal);
                    meshes.get(i).setOpacity(targetOpacity);
                }
            }
        }

        // 3. Reset parameter to default value
        parameter.resetToDefault();
        model.update();
    }

    private static Map<Float, Float> calculateOpacitiesForMesh(
            final int index,
            final int totalFrames,
            final float minValue,
            final float maxValue,
            final float defaultValue,
            final boolean isRepeat,
            final DisplayStrategy strategy,
            final KeyformBindingMode bindingMode) {
        final Map<Float, Float> map = new HashMap<>();

        if (bindingMode == KeyformBindingMode.FULL_RANGE) {
            // Standard clean integer frame keys (0..totalFrames-1)
            for (int k = 0; k < totalFrames; k++) {
                map.put((float) k, (k == index) ? 1.0f : 0.0f);
            }
            if (strategy == DisplayStrategy.STEP_FLIPBOOK) {
                // Add guard keys for discrete steps
                if (index < totalFrames - 1) {
                    map.put((float) index + 1.0f - EPSILON, 1.0f);
                }
                if (index > 0) {
                    map.put((float) index - EPSILON, 0.0f);
                }
            }
        } else {
            // LOCAL_NEIGHBORHOOD
            if (strategy == DisplayStrategy.CROSSFADE) {
                if (index > 0) {
                    map.put((float) (index - 1), 0.0f);
                }
                map.put((float) index, 1.0f);
                if (index < totalFrames - 1) {
                    map.put((float) (index + 1), 0.0f);
                }
            } else {
                // STEP_FLIPBOOK
                final float cur = (float) index;
                final float next = cur + 1.0f;
                final float guardEnd = next - EPSILON;
                final float guardStart = cur - EPSILON;

                if (index > 0) {
                    map.put(guardStart, 0.0f);
                }
                map.put(cur, 1.0f);

                if (index < totalFrames - 1) {
                    map.put(guardEnd, 1.0f);
                    map.put(next, 0.0f);
                }
            }
        }

        // Live2D Cubism requires every bound ArtMesh to include the parameter's default key.
        map.putIfAbsent(defaultValue, (index == 0) ? 1.0f : 0.0f);

        // When a parameter has repeat=true, Live2D's native KeyformGridSource.addKey
        // mandates that both minValue and maxValue must be present in the binding.
        if (isRepeat) {
            map.putIfAbsent(minValue, (index == 0) ? 1.0f : 0.0f);
            map.putIfAbsent(maxValue, (index == totalFrames - 1) ? 1.0f : 0.0f);
        }

        return Collections.unmodifiableMap(map);
    }
}
