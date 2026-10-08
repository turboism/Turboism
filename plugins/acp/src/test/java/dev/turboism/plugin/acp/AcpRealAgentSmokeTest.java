package dev.turboism.plugin.acp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real-process smoke test: launches each installed catalog agent through
 * {@link AcpProcessTransport} and completes the initialize + session/new handshake over live
 * stdio. Runs only when the agent executable is installed and the environment opts in via
 * {@code TURBOISM_ACP_SMOKE=1}; otherwise every case is skipped.
 */
final class AcpRealAgentSmokeTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void devinCliCompletesTheAcpHandshake() throws Exception {
        smoke("devin");
    }

    @Test
    void opencodeCompletesTheAcpHandshake() throws Exception {
        smoke("opencode");
    }

    private void smoke(final String agentId) throws Exception {
        assumeTrue(
                "1".equals(System.getenv("TURBOISM_ACP_SMOKE")),
                "set TURBOISM_ACP_SMOKE=1 to run real agent smoke tests");
        final AgentProfile profile =
                AgentCatalog.profile(agentId).orElseThrow(() -> new AssertionError(agentId));
        final Path executable = AgentLocator.locate(profile)
                .orElse(null);
        assumeTrue(executable != null, agentId + " executable is not installed");

        final java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add(executable.toString());
        argv.addAll(profile.arguments());
        final AgentLaunchSpec spec = new AgentLaunchSpec(List.copyOf(argv), temporaryDirectory);
        try (AcpClient client = AcpClient.start(spec, new AcpListener() {})) {
            assertNotNull(client.agentInfo());
            assertFalse(client.agentInfo().name().isBlank());
            final AcpSession session = client.newSession(
                    temporaryDirectory, null, Duration.ofSeconds(30));
            assertNotNull(session.sessionId());
            assertFalse(session.sessionId().isBlank());
        }
    }
}
