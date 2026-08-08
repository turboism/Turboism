package dev.turboism.sdk.cubism.core;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;

import java.util.List;
import java.util.Objects;

/**
 * Immutable adapter-owned projection of one evaluated Core drawable.
 *
 * <p>The blend mode is version-normalized by the runtime: 5.3.02 reads the Core
 * {@code getBlendModes()} array directly, 5.2.03 derives it from the drawable constant
 * flags ({@code BLEND_ADDITIVE=1}, {@code BLEND_MULTIPLICATIVE=2}) because the 5.2
 * public surface has no blend-mode getter.</p>
 */
@PreviewApi
public record OwnedDrawable(
    String id,
    byte constantFlag,
    byte dynamicFlag,
    BlendMode blendMode,
    int textureIndex,
    int drawOrder,
    int renderOrder,
    float opacity,
    List<Integer> masks,
    List<Float> vertexPositions,
    List<Float> vertexUvs,
    List<Integer> indices,
    Color multiplyColor,
    Color screenColor,
    int parentPartIndex,
    int parentDeformerIndex,
    List<Integer> parameters
) {

    public OwnedDrawable {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(blendMode, "blendMode");
        Objects.requireNonNull(multiplyColor, "multiplyColor");
        Objects.requireNonNull(screenColor, "screenColor");
        masks = List.copyOf(masks);
        vertexPositions = List.copyOf(vertexPositions);
        vertexUvs = List.copyOf(vertexUvs);
        indices = List.copyOf(indices);
        parameters = List.copyOf(parameters);
        if (id.isBlank() || textureIndex < 0 || !Float.isFinite(opacity)
            || parentPartIndex < -1 || parentDeformerIndex < -1) {
            throw new IllegalArgumentException("invalid OwnedDrawable definition");
        }
    }
}
