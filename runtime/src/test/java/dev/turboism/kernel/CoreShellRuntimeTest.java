package dev.turboism.kernel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The shell handle's remaining responsibilities: the framework admission seam and the deadline
 * ordering between shell admission and plugin loads.
 *
 * <p>Admission and teardown themselves belong to {@link AdmittedGeneration} and are covered by
 * {@code AdmittedGenerationTest}; the shell adapter's resource roots, EDT-readiness budget and
 * reserved identity are covered by {@code ShellGenerationContentTest}.</p>
 */
class CoreShellRuntimeTest {

    @Test
    void productionShellDeadlineOutlastsPluginLoads() {
        final PluginLifecyclePolicy production = PluginLifecyclePolicy.production();
        assertTrue(
                production.shellLoadTimeout().compareTo(production.loadTimeout()) > 0,
                "the shell builds host UI synchronously and needs a wider deadline than a plugin load");
    }
}
