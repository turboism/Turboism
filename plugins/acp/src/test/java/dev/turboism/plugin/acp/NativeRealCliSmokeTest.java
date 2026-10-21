package dev.turboism.plugin.acp;

import static dev.turboism.plugin.acp.NativeAgentTransport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.sdk.json.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Isolated, opt-in real binary handshake. Never sends a model prompt or reads user credentials. */
@EnabledIfEnvironmentVariable(named = "TURBOISM_NATIVE_SMOKE", matches = "1")
final class NativeRealCliSmokeTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @EnumSource(
            value = AgentProtocol.class,
            names = {"CODEX", "CLAUDE", "PI"})
    void createsNativeSessionWithoutExternalAdapter(AgentProtocol protocol) throws Exception {
        var profile = AgentCatalog.profile(
                        protocol == AgentProtocol.CLAUDE
                                ? "claude"
                                : protocol.name().toLowerCase(Locale.ROOT))
                .orElseThrow();
        var executable =
                AgentLocator.findExecutable(profile.executableCandidates().get(0));
        assumeTrue(executable.isPresent(), "Original CLI is not installed");
        Files.createDirectories(directory.resolve("codex"));
        var environment = Map.of(
                "HOME",
                directory.toString(),
                "CODEX_HOME",
                directory.resolve("codex").toString(),
                "CLAUDE_CONFIG_DIR",
                directory.resolve("claude").toString(),
                "PI_CODING_AGENT_DIR",
                directory.resolve("pi").toString());
        try (var transport = new NativeAgentTransport(new AgentLaunchSpec(
                        List.of(executable.orElseThrow().toString()), directory, environment, protocol));
                var output = new BufferedReader(new InputStreamReader(transport.stdout(), StandardCharsets.UTF_8))) {
            write(transport, Map.of("id", 1L, "method", "initialize"));
            assertTrue(read(output).containsKey("result"));
            write(
                    transport,
                    Map.of(
                            "id",
                            2L,
                            "method",
                            "session/new",
                            "params",
                            Map.of("cwd", directory.toString(), "mcpServers", List.of())));
            var reply = read(output);
            assertFalse(reply.containsKey("error"), () -> Json.stringify(reply));
            assertFalse(text(object(reply.get("result")).get("sessionId")).isBlank());
            assertFalse(
                    list(object(reply.get("result")).get("configOptions")).isEmpty() && protocol != AgentProtocol.PI);
        }
    }

    private static void write(AcpTransport transport, Map<String, Object> message) throws IOException {
        transport.stdin().write((Json.stringify(message) + "\n").getBytes(StandardCharsets.UTF_8));
        transport.stdin().flush();
    }

    private static Map<String, Object> read(BufferedReader output) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
                    try {
                        return object(Json.parseObject(output.readLine().getBytes(StandardCharsets.UTF_8)));
                    } catch (IOException failure) {
                        throw new CompletionException(failure);
                    }
                })
                .get(25, TimeUnit.SECONDS);
    }
}
