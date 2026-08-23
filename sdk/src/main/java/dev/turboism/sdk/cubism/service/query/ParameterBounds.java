package dev.turboism.sdk.cubism.service.query;

/**
 * The value range and rest position the Editor defines for a parameter.
 *
 * <p>A plain value carrier: it validates nothing, so callers must not assume
 * {@code minValue <= defaultValue <= maxValue} unless the producing host guarantees it.
 *
 * @param minValue lowest value the Editor allows for the parameter
 * @param maxValue highest value the Editor allows for the parameter
 * @param defaultValue the parameter's rest value, what it returns to when reset
 */
public record ParameterBounds(
    double minValue,
    double maxValue,
    double defaultValue
) {
}
