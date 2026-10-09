package dev.turboism.tests.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.bootstrap.HostRuntimeIngress;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.preview.LocalPluginRuntime;
import dev.turboism.preview.PreviewLog;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Loads the real production {@code mcp.jar} through the same PreviewPluginLoader →
 * CorePluginContext → McpPlugin.enable wiring used on the host, then proves the loopback
 * transport answers a real {@code initialize} request. This is the seam where a plugin jar
 * compiled against a different SDK generation fails with NoSuchMethodError on the host:
 * every SDK member referenced during context extraction must resolve against the runtime
 * classpath for the plugin to reach ENABLED.
 */
final class McpPluginRuntimeIntegrationTest {

    private static final String PORT_PROPERTY = "turboism.mcp.port";
    private static final String MCP_PLUGIN_ID = "dev.turboism.plugin.mcp";

    @TempDir
    Path temporaryDirectory;

    @Test
    @ResourceLock(PORT_PROPERTY)
    void productionMcpPluginJarEnablesAndServesInitialize() throws Exception {
        final Path home = temporaryDirectory.resolve("mcp-preview-home");
        installMcpPluginJar(home.resolve("plugins"));

        final String previousPort = System.getProperty(PORT_PROPERTY);
        System.setProperty(PORT_PROPERTY, "0");
        try {
            loadEnableAndProbe(home);
        } finally {
            if (previousPort == null) {
                System.clearProperty(PORT_PROPERTY);
            } else {
                System.setProperty(PORT_PROPERTY, previousPort);
            }
        }
    }

    private static void loadEnableAndProbe(final Path home) throws Exception {
        final PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"));
        final RuntimeScheduler scheduler = scheduler();
        final HostRuntimeIngress hostIngress = new HostRuntimeIngress();
        final LocalPluginRuntime runtime = new LocalPluginRuntime(home, scheduler, hostIngress.adapterAccess(), log);
        try {
            final LocalPluginRuntime.LoadReport report = runtime.loadAll();
            assertTrue(
                    report.failures().isEmpty(),
                    "mcp plugin must load without failures; failures=" + report.failures());
            assertEquals(1, report.loaded().size(), "exactly one plugin expected; loaded=" + report.loaded());
            assertEquals(MCP_PLUGIN_ID, report.loaded().get(0).id());
            assertEquals(
                    "ENABLED",
                    report.loaded().get(0).state().name(),
                    "McpPlugin.enable must complete through the production PluginContext wiring");

            final Path connectionFile = findConnectionFile(home);
            assertTrue(
                    Files.isRegularFile(connectionFile),
                    "mcp-connection.json must be published under the plugin state dir");
            assertInitializeAnswers(connectionFile);
        } finally {
            runtime.close();
            hostIngress.close();
            scheduler.shutdown();
            log.close();
        }
    }

    private static void assertInitializeAnswers(final Path connectionFile) throws Exception {
        final ObjectMapper mapper = new ObjectMapper();
        final JsonNode connection = mapper.readTree(Files.readAllBytes(connectionFile));
        final URI endpoint = URI.create(connection.get("endpoint").asText());
        final String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"preview-wiring-test\",\"version\":\"0\"}}}";
        final HttpResponse<byte[]> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(endpoint)
                                .timeout(Duration.ofSeconds(10))
                                .header("Accept", "application/json, text/event-stream")
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode(), "initialize must be answered on the bound loopback endpoint");
        final JsonNode result = mapper.readTree(response.body()).get("result");
        assertEquals("turboism-mcp", result.get("serverInfo").get("name").asText());
    }

    private static Path findConnectionFile(final Path home) throws IOException {
        try (var files = Files.walk(home)) {
            final Optional<Path> match = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("mcp-connection.json"))
                    .findFirst();
            return match.orElse(home.resolve("mcp-connection.json"));
        }
    }

    private static void installMcpPluginJar(final Path plugins) throws IOException {
        Files.createDirectories(plugins);
        final Path libs = Path.of(System.getProperty("mcpBuildDir")).resolve("libs");
        try (var files = Files.list(libs)) {
            final Path jar = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .min(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElseThrow(() -> new IOException("MCP plugin JAR was not built under " + libs));
            Files.copy(jar, plugins.resolve("mcp.jar"));
        }
    }

    private static RuntimeScheduler scheduler() {
        return new RuntimeScheduler(
                new DefaultWorkBudgetPolicy(),
                new PluginWorkExecutorRegistry(1, 16, ignored -> {}, Clock.systemUTC()),
                SidecarDispatcher.noop(),
                ignored -> {});
    }
}
