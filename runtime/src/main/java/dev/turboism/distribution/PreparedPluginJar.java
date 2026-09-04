package dev.turboism.distribution;

import java.nio.file.Path;
import java.util.Objects;

/** Runtime-private installation evidence produced from a directly selected plugin JAR. */
public record PreparedPluginJar(
    PluginDescriptorSnapshot descriptor,
    String descriptorSha256,
    Path stagedJar,
    String jarSha256,
    long jarSize
) {
    /** Validates and normalizes direct-JAR installation evidence. */
    public PreparedPluginJar {
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        descriptorSha256 = digest(descriptorSha256, "descriptorSha256");
        stagedJar = Objects.requireNonNull(stagedJar, "stagedJar").toAbsolutePath().normalize();
        jarSha256 = digest(jarSha256, "jarSha256");
        if (jarSize < 0L) throw new IllegalArgumentException("jarSize must not be negative");
    }

    private static String digest(final String value, final String name) {
        final String required = Objects.requireNonNull(value, name);
        if (!required.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid " + name);
        return required;
    }
}
