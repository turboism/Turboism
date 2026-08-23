package dev.turboism.core.runtime.sidecar;

import java.util.List;

/**
 * How {@link ProcessSidecarDispatcher} should launch its worker JVM.
 *
 * @param enabled       when {@code false}, every dispatch is refused with a
 *                      {@code SIDECAR_DISABLED} {@link SidecarDispatchException}
 * @param javaBinary    path to the java executable to launch, non-blank
 * @param classpath     defensively copied, immutable classpath entries; {@code null}
 *                      becomes empty, in which case no {@code -cp} argument is passed
 * @param mainClass     fully qualified worker entry-point class, non-blank
 * @param timeoutMillis wall-clock budget per run before the process is destroyed;
 *                      must be positive
 * @throws IllegalArgumentException when {@code javaBinary} or {@code mainClass} is
 *     null or blank, or {@code timeoutMillis} is not positive
 */
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
