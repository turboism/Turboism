package dev.turboism.plugin.parametersync.domain;

/**
 * Mapping strategy used to transform parameter values during synchronization.
 */
public enum SyncMode {

    /**
     * Maps the source parameter's normalized range [min, max] to the target parameter's range.
     * Ideal when parameter bounds differ (e.g. [-30, 30] to [0, 1]).
     */
    NORMALIZED_RANGE,

    /**
     * Maps the source parameter's normalized range inversely to the target parameter's range
     * (e.g. when source is at min, target is at max).
     */
    NORMALIZED_INVERTED,

    /**
     * Copies the source value directly to the target, clamping to the target's range.
     */
    DIRECT,

    /**
     * Calculates the target value linearly: target = source * ratio + offset, clamped to target range.
     */
    RATIO;

    /**
     * Calculates the target value given the source value and the bounds of both parameters.
     */
    public float computeTarget(
            final float sourceValue,
            final float srcMin,
            final float srcMax,
            final float tgtMin,
            final float tgtMax,
            final float ratio,
            final float offset) {
        return switch (this) {
            case NORMALIZED_RANGE -> {
                final float t = normalize(sourceValue, srcMin, srcMax);
                yield clamp(tgtMin + t * (tgtMax - tgtMin), tgtMin, tgtMax);
            }
            case NORMALIZED_INVERTED -> {
                final float t = normalize(sourceValue, srcMin, srcMax);
                yield clamp(tgtMax - t * (tgtMax - tgtMin), tgtMin, tgtMax);
            }
            case DIRECT -> clamp(sourceValue, tgtMin, tgtMax);
            case RATIO -> clamp(sourceValue * ratio + offset, tgtMin, tgtMax);
        };
    }

    /**
     * Calculates the reverse source value given the target value and the bounds of both parameters.
     * Used for bidirectional synchronization.
     */
    public float computeSource(
            final float targetValue,
            final float srcMin,
            final float srcMax,
            final float tgtMin,
            final float tgtMax,
            final float ratio,
            final float offset) {
        return switch (this) {
            case NORMALIZED_RANGE -> {
                final float t = normalize(targetValue, tgtMin, tgtMax);
                yield clamp(srcMin + t * (srcMax - srcMin), srcMin, srcMax);
            }
            case NORMALIZED_INVERTED -> {
                final float t = normalize(targetValue, tgtMin, tgtMax);
                yield clamp(srcMax - t * (srcMax - srcMin), srcMin, srcMax);
            }
            case DIRECT -> clamp(targetValue, srcMin, srcMax);
            case RATIO -> {
                if (Math.abs(ratio) < 1e-6f) {
                    yield clamp(srcMin, srcMin, srcMax);
                }
                final float unscaled = (targetValue - offset) / ratio;
                yield clamp(unscaled, srcMin, srcMax);
            }
        };
    }

    private static float normalize(final float value, final float min, final float max) {
        final float range = max - min;
        if (Math.abs(range) < 1e-6f) {
            return 0.5f;
        }
        final float clamped = Math.max(min, Math.min(max, value));
        return (clamped - min) / range;
    }

    private static float clamp(final float value, final float min, final float max) {
        return Math.max(min, Math.min(max, value));
    }
}
