package dev.turboism.sdk.config;

public record ConfigKey<T>(
    String configId,
    String name,
    T defaultValue,
    ConfigCodec<T> codec
) {
}
