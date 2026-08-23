package dev.turboism.sdk.cubism.model;


import java.util.OptionalInt;

/** Immutable statistics for one active Cubism model generation. */
public record ModelStatistics(
    int parameterCount,
    int partCount,
    int drawableCount,
    int artMeshCount,
    int deformerCount,
    int vertexCount,
    int triangleCount,
    int textureCount,
    int maskedDrawableCount,
    int maskGroupCount,
    OptionalInt offscreenRenderingCount,
    OptionalInt maxOffscreenDepth
) {
    public ModelStatistics {
        requireNonNegative(parameterCount, "parameterCount");
        requireNonNegative(partCount, "partCount");
        requireNonNegative(drawableCount, "drawableCount");
        requireNonNegative(artMeshCount, "artMeshCount");
        requireNonNegative(deformerCount, "deformerCount");
        requireNonNegative(vertexCount, "vertexCount");
        requireNonNegative(triangleCount, "triangleCount");
        requireNonNegative(textureCount, "textureCount");
        requireNonNegative(maskedDrawableCount, "maskedDrawableCount");
        requireNonNegative(maskGroupCount, "maskGroupCount");
        offscreenRenderingCount = requireNonNegative(offscreenRenderingCount, "offscreenRenderingCount");
        maxOffscreenDepth = requireNonNegative(maxOffscreenDepth, "maxOffscreenDepth");
    }

    private static void requireNonNegative(final int value, final String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
    }

    private static OptionalInt requireNonNegative(final OptionalInt value, final String name) {
        if (value == null) throw new NullPointerException(name);
        if (value.isPresent()) requireNonNegative(value.getAsInt(), name);
        return value;
    }
}
