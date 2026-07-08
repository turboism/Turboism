package dev.turboism.core.runtime.sidecar;

import java.util.List;

public record SidecarDispatcherConfiguration(
    boolean enabled,
    String javaBinary,
    List<String> classpath,
    String mainClass,
    long timeoutMillis
) {

    public SidecarDispatcherConfiguration {
        if (javaBinary == null || javaBinary.isBlank()) {
            throw new IllegalArgumentException("javaBinary must not be blank");
        }
        classpath = classpath == null ? List.of() : List.copyOf(classpath);
        if (mainClass == null || mainClass.isBlank()) {
            throw new IllegalArgumentException("mainClass must not be blank");
        }
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
    }
}
