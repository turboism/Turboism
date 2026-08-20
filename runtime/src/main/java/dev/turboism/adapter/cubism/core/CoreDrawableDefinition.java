package dev.turboism.adapter.cubism.core;

import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;

import java.util.List;
import java.util.Objects;

/**
 * Immutable value snapshot of one Cubism Core drawable, holding only plain data so no raw Core
 * object escapes the adapter. All list components are defensively copied by the compact
 * constructor, so the record is safe to share across threads.
 *
 * <p>The constructor rejects a blank {@code id}, a negative {@code textureIndex}, a non-finite
 * {@code opacity}, or a parent index below -1, throwing {@link IllegalArgumentException}.</p>
 *
 * @param id Core drawable identifier, never blank
 * @param constantFlag Core constant flag bits, verbatim
 * @param dynamicFlag Core dynamic flag bits as of this snapshot
 * @param blendMode blend mode decoded from the Core flags
 * @param textureIndex index into the model's texture list, never negative
 * @param drawOrder authored draw order value
 * @param renderOrder resolved render order value
 * @param opacity drawable opacity, always finite
 * @param masks indices of drawables masking this one; empty when unmasked
 * @param vertexPositions flattened x,y vertex positions
 * @param vertexUvs flattened u,v texture coordinates
 * @param indices triangle indices into the vertex arrays
 * @param multiplyColor multiply colour applied to this drawable
 * @param screenColor screen colour applied to this drawable
 * @param parentPartIndex index of the owning part, or -1 when unparented
 * @param parentDeformerIndex index of the owning deformer, or -1 when unparented
 * @param parameters indices of the parameters that drive this drawable
 */
public record CoreDrawableDefinition(
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
    public CoreDrawableDefinition {
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
            throw new IllegalArgumentException("invalid Core Drawable definition");
        }
    }
}
