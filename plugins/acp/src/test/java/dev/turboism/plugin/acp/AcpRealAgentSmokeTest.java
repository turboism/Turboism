package dev.turboism.plugin.acp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies ACP discovery and session startup with real CLI processes.
 * Antigravity can request sign-in before it creates a session.
 * Each case requires the corresponding executable and {@code TURBOISM_ACP_SMOKE=1}.
 */
final class AcpRealAgentSmokeTest {

    private static final Duration SESSION_TIMEOUT = Duration.ofSeconds(30);

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

    @Test
    void antigravityCompletesTheHandshakeAndCreatesSessionOrRequestsSignIn() throws Exception {
        smoke("antigravity", true);
    }

    @Test
    void deepSeekHarnessCreatesAnAcpSession() throws Exception {
        final AcpSession session = smoke("deepseek", false).orElseThrow();
        assertNotNull(session.option("model"), "DeepSeek Harness must expose its model choices");
    }

    private void smoke(final String agentId) throws Exception {
        smoke(agentId, false);
    }

    private Optional<AcpSession> smoke(final String agentId, final boolean allowsSignIn) throws Exception {
        assumeTrue(
                "1".equals(System.getenv("TURBOISM_ACP_SMOKE")),
                "set TURBOISM_ACP_SMOKE=1 to run real agent smoke tests");
        final AgentProfile profile = AgentCatalog.profile(agentId).orElseThrow(() -> new AssertionError(agentId));
        final Path executable = AgentLocator.locate(profile).orElse(null);
        assumeTrue(executable != null, agentId + " executable is not installed");
        assertTrue(AgentAvailability.available(profile, executable), "ACP discovery rejected " + executable);

        final java.util.List<String> argv = new java.util.ArrayList<>();
        argv.add(executable.toString());
        argv.addAll(profile.argumentsFor(executable));
        // Keep the process directory independent of the session workspace, as the plugin does.
        final AgentLaunchSpec spec = new AgentLaunchSpec(
                List.copyOf(argv), Path.of(".").toAbsolutePath().normalize());
        try (AcpClient client = AcpClient.start(spec, new AcpListener() {})) {
            assertNotNull(client.agentInfo());
            assertFalse(client.agentInfo().name().isBlank());
            try {
                final AcpSession session = client.newSession(temporaryDirectory, null, SESSION_TIMEOUT);
                assertFalse(session.sessionId().isBlank());
                if (session.capabilities().closeSession()) {
                    client.closeSession(session.sessionId(), SESSION_TIMEOUT);
                }
                return Optional.of(session);
            } catch (AcpException failure) {
                if (!allowsSignIn || !failure.authRequired()) throw failure;
                assertFalse(client.authMethods().isEmpty(), "The agent must advertise a sign-in method");
                return Optional.empty();
            }
        }
    }
}
