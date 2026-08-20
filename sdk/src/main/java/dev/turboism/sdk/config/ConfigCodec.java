package dev.turboism.sdk.config;

/**
 * Converts a typed config value to and from the string form stored in a {@link ConfigDocument}.
 *
 * <p>Only the codec identity is exposed on this SDK-facing interface; the encode/decode work is
 * performed by the runtime that owns the persisted document.
 *
 * @param <T> the value type this codec describes
 */
public interface ConfigCodec<T> {

    String typeId();
}
