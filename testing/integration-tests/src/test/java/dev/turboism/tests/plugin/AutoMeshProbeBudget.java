package dev.turboism.tests.plugin;

import java.time.Duration;

/**
 * Reads one probe budget from a system property, falling back to a documented default.
 *
 * <p>A malformed or non-positive value falls back instead of failing the run: a probe that cannot
 * read its own budget must still produce a verdict, and the budget only bounds how long a step may
 * wait.</p>
 */
final class AutoMeshProbeBudget {

    private AutoMeshProbeBudget() {}

    /**
     * Reads a budget in seconds.
     *
     * @param property the system property name
     * @param fallback the budget used when the property is absent, blank, malformed or not positive
     * @return the resolved budget
     */
    static Duration read(final String property, final Duration fallback) {
        final String configured = System.getProperty(property, "").trim();
        if (configured.isEmpty()) {
            return fallback;
        }
        try {
            final long seconds = Long.parseLong(configured);
            return seconds > 0 ? Duration.ofSeconds(seconds) : fallback;
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }
}
