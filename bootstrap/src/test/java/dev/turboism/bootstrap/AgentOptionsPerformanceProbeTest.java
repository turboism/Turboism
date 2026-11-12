package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentOptionsPerformanceProbeTest {

    @Test
    void probeDefaultsOffAndRequiresStrictBooleans() {
        final AgentOptions defaults = AgentOptions.parse(null, Path.of("home"));
        assertFalse(defaults.performanceProbeInstall());
        assertFalse(defaults.performanceProbeCapture());

        final AgentOptions enabled =
                AgentOptions.parse("performanceProbeInstall=true;performanceProbeCapture=true", Path.of("home"));
        assertTrue(enabled.performanceProbeInstall());
        assertTrue(enabled.performanceProbeCapture());
        assertTrue(enabled.performanceProbeDurationSeconds() == 30);
        assertTrue(enabled.performanceProbeOutput().isAbsolute());
        assertTrue(enabled.performanceProbeScenario().equals("camera"));

        assertThrows(
                IllegalArgumentException.class,
                () -> AgentOptions.parse("performanceProbeInstall=yes", Path.of("home")));
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentOptions.parse("performanceProbeDurationSeconds=4", Path.of("home")));
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentOptions.parse("performanceProbeScenario=unknown", Path.of("home")));
    }

    @Test
    void legacyAgentAndFixtureDigestOptionsAreNoLongerAccepted() {
        final String capture = "performanceProbeInstall=true;performanceProbeCapture=true";
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentOptions.parse(capture + ";performanceProbeAgentSha256=" + "a".repeat(64), Path.of("home")));
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentOptions.parse(
                        capture + ";performanceProbeFixtureSha256=" + "b".repeat(64), Path.of("home")));
    }

    @Test
    void captureWithoutInstallIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentOptions.parse("performanceProbeCapture=true", Path.of("home")));
        final AgentOptions accepted =
                AgentOptions.parse("performanceProbeInstall=true;performanceProbeCapture=true", Path.of("home"));
        assertTrue(accepted.performanceProbeInstall());
        assertTrue(accepted.performanceProbeCapture());
    }

    @Test
    void captureOffKeepsScenarioDelayAndDurationBounds() {
        final String base = "performanceProbeInstall=true;performanceProbeCapture=false";
        final AgentOptions unbound = AgentOptions.parse(base, Path.of("home"));
        assertTrue(unbound.performanceProbeRunId().equals("unbound"));

        final AgentOptions bounded = AgentOptions.parse(
                base + ";performanceProbeDelaySeconds=300;performanceProbeDurationSeconds=120", Path.of("home"));
        assertTrue(bounded.performanceProbeDelaySeconds() == 300);
        assertTrue(bounded.performanceProbeDurationSeconds() == 120);

        assertThrows(
                IllegalArgumentException.class,
                () -> AgentOptions.parse(
                        base + ";performanceProbeDelaySeconds=-1;performanceProbeDurationSeconds=121",
                        Path.of("home")));
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentOptions.parse(
                        base + ";performanceProbeDelaySeconds=301;performanceProbeDurationSeconds=4", Path.of("home")));
    }

    @Test
    void rollbackOptionsBindRunIdAndOutputButStayOffByDefault() {
        final AgentOptions defaults = AgentOptions.parse(null, Path.of("home"));
        assertTrue(defaults.performanceProbeRunId().equals("unbound"));
        assertTrue(defaults.performanceProbeRollbackOutput() == null);

        final AgentOptions bound = AgentOptions.parse(
                "performanceProbeInstall=true;performanceProbeRunId=perf-20260804T120000Z-01;"
                        + "performanceProbeRollbackOutput=Z:\\runs\\rollback-manifest.json",
                Path.of("home"));
        assertTrue(bound.performanceProbeRunId().equals("perf-20260804T120000Z-01"));
        assertTrue(bound.performanceProbeRollbackOutput() != null);
        assertTrue(bound.performanceProbeRollbackOutput().isAbsolute());

        assertThrows(
                IllegalArgumentException.class,
                () -> AgentOptions.parse("performanceProbeRollbackOutput=Z:\\runs\\m.json", Path.of("home")));
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentOptions.parse("performanceProbeRunId=-bad", Path.of("home")));
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentOptions.parse("performanceProbeRunId=with space", Path.of("home")));
    }
}
