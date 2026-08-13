package dev.turboism.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class AgentOptionsPerformanceProbeTest {

    private static final String AGENT_SHA = "a".repeat(64);
    private static final String FIXTURE_SHA = "b".repeat(64);

    @Test
    void probeDefaultsOffAndRequiresStrictBooleans() {
        final AgentOptions defaults = AgentOptions.parse(null, Path.of("home"));
        assertFalse(defaults.performanceProbeInstall());
        assertFalse(defaults.performanceProbeCapture());

        final AgentOptions enabled = AgentOptions.parse(
            "performanceProbeInstall=true;performanceProbeCapture=true;"
                + "performanceProbeAgentSha256=" + AGENT_SHA
                + ";performanceProbeFixtureSha256=" + FIXTURE_SHA,
            Path.of("home")
        );
        assertTrue(enabled.performanceProbeInstall());
        assertTrue(enabled.performanceProbeCapture());
        assertTrue(enabled.performanceProbeDurationSeconds() == 30);
        assertTrue(enabled.performanceProbeOutput().isAbsolute());
        assertTrue(enabled.performanceProbeScenario().equals("camera"));

        assertThrows(IllegalArgumentException.class, () ->
            AgentOptions.parse("performanceProbeInstall=yes", Path.of("home"))
        );
        assertThrows(IllegalArgumentException.class, () ->
            AgentOptions.parse("performanceProbeDurationSeconds=4", Path.of("home"))
        );
        assertThrows(IllegalArgumentException.class, () ->
            AgentOptions.parse("performanceProbeScenario=unknown", Path.of("home"))
        );
    }

    @Test
    void captureRequiresBoundLowercaseSha256Identities() {
        final String capture = "performanceProbeInstall=true;performanceProbeCapture=true";
        assertThrows(IllegalArgumentException.class, () ->
            AgentOptions.parse(capture, Path.of("home"))
        );
        assertThrows(IllegalArgumentException.class, () ->
            AgentOptions.parse(
                capture + ";performanceProbeAgentSha256=" + AGENT_SHA,
                Path.of("home")
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            AgentOptions.parse(
                capture + ";performanceProbeAgentSha256=unbound;"
                    + "performanceProbeFixtureSha256=" + FIXTURE_SHA,
                Path.of("home")
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            AgentOptions.parse(
                capture + ";performanceProbeAgentSha256=" + AGENT_SHA.toUpperCase(java.util.Locale.ROOT)
                    + ";performanceProbeFixtureSha256=" + FIXTURE_SHA,
                Path.of("home")
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            AgentOptions.parse(
                capture + ";performanceProbeAgentSha256=" + AGENT_SHA.substring(0, 63)
                    + ";performanceProbeFixtureSha256=" + FIXTURE_SHA,
                Path.of("home")
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            AgentOptions.parse(
                capture + ";performanceProbeAgentSha256=" + "z".repeat(64)
                    + ";performanceProbeFixtureSha256=" + FIXTURE_SHA,
                Path.of("home")
            )
        );
        final AgentOptions bound = AgentOptions.parse(
            capture + ";performanceProbeAgentSha256=" + AGENT_SHA
                + ";performanceProbeFixtureSha256=" + FIXTURE_SHA,
            Path.of("home")
        );
        assertTrue(bound.performanceProbeAgentSha256().equals(AGENT_SHA));
        assertTrue(bound.performanceProbeFixtureSha256().equals(FIXTURE_SHA));
    }

    @Test
    void captureWithoutInstallIsRejected() {
        final String boundIdentities = "performanceProbeCapture=true;"
            + "performanceProbeAgentSha256=" + AGENT_SHA
            + ";performanceProbeFixtureSha256=" + FIXTURE_SHA;
        assertThrows(IllegalArgumentException.class, () ->
            AgentOptions.parse(boundIdentities, Path.of("home"))
        );
        final AgentOptions accepted = AgentOptions.parse(
            "performanceProbeInstall=true;" + boundIdentities,
            Path.of("home")
        );
        assertTrue(accepted.performanceProbeInstall());
        assertTrue(accepted.performanceProbeCapture());
    }
    @Test
    void captureOffKeepsScenarioDelayAndDurationBounds() {
        final String base = "performanceProbeInstall=true;performanceProbeCapture=false";
        final AgentOptions unbound = AgentOptions.parse(base, Path.of("home"));
        assertTrue(unbound.performanceProbeAgentSha256().equals("unbound"));
        assertTrue(unbound.performanceProbeFixtureSha256().equals("unbound"));

        final AgentOptions bounded = AgentOptions.parse(
            base + ";performanceProbeDelaySeconds=300;performanceProbeDurationSeconds=120",
            Path.of("home")
        );
        assertTrue(bounded.performanceProbeDelaySeconds() == 300);
        assertTrue(bounded.performanceProbeDurationSeconds() == 120);

        assertThrows(IllegalArgumentException.class, () ->
            AgentOptions.parse(
                base + ";performanceProbeDelaySeconds=-1;performanceProbeDurationSeconds=121",
                Path.of("home")
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            AgentOptions.parse(
                base + ";performanceProbeDelaySeconds=301;performanceProbeDurationSeconds=4",
                Path.of("home")
            )
        );
    }

    @Test
    void rollbackOptionsBindRunIdAndOutputButStayOffByDefault() {
        final AgentOptions defaults = AgentOptions.parse(null, Path.of("home"));
        assertTrue(defaults.performanceProbeRunId().equals("unbound"));
        assertTrue(defaults.performanceProbeRollbackOutput() == null);

        final AgentOptions bound = AgentOptions.parse(
            "performanceProbeInstall=true;performanceProbeRunId=perf-20260804T120000Z-01;"
                + "performanceProbeRollbackOutput=Z:\\runs\\rollback-manifest.json",
            Path.of("home")
        );
        assertTrue(bound.performanceProbeRunId().equals("perf-20260804T120000Z-01"));
        assertTrue(bound.performanceProbeRollbackOutput() != null);
        assertTrue(bound.performanceProbeRollbackOutput().isAbsolute());

        assertThrows(IllegalArgumentException.class, () ->
            AgentOptions.parse("performanceProbeRollbackOutput=Z:\\runs\\m.json", Path.of("home"))
        );
        assertThrows(IllegalArgumentException.class, () ->
            AgentOptions.parse("performanceProbeRunId=-bad", Path.of("home"))
        );
        assertThrows(IllegalArgumentException.class, () ->
            AgentOptions.parse("performanceProbeRunId=with space", Path.of("home"))
        );
    }
}
