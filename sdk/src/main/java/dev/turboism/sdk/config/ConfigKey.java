package dev.turboism.sdk.config;

/**
 * Identifies one typed config entry within a registered {@link ConfigSchema}.
 *
 * <p>This is a plain carrier: it performs no validation of its own. Legality of the identifiers,
 * codec and default value is checked when the owning schema is registered.
 *
 * @param configId the schema this key belongs to
 * @param name the key name as it appears in the persisted document
 * @param defaultValue the value returned when no usable stored value exists
 * @param codec the codec describing how the value is encoded
 * @param <T> the value type
 */
public record ConfigKey<T>(
    String configId,
    String name,
    T defaultValue,
    ConfigCodec<T> codec
) {
}
