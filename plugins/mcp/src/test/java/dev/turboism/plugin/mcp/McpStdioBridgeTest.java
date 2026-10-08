package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.json.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class McpStdioBridgeTest {

    @TempDir
    Path stateDir;

    @Test
    void publishesABridgeSourceBoundToTheStateDirectory() throws Exception {
        final Path bridge = McpStdioBridge.publish(stateDir);

        assertEquals(stateDir.resolve(McpStdioBridge.FILE_NAME), bridge);
        final String source = Files.readString(bridge, StandardCharsets.UTF_8);
        assertTrue(source.contains(stateDir.toAbsolutePath().toString().replace("\\", "\\\\")));
        assertTrue(source.contains("mcp.token"));
        assertTrue(source.contains("mcp-connection.json"));
        assertTrue(source.contains("Authorization\", \"Bearer "));
        assertTrue(source.contains("class TurboismMcpBridge"));

        // An unchanged source is not republished.
        final long written = Files.getLastModifiedTime(bridge).toMillis();
        assertEquals(bridge, McpStdioBridge.publish(stateDir));
        assertEquals(written, Files.getLastModifiedTime(bridge).toMillis());
    }

    @Test
    void publishedBridgeCompilesAsAStandaloneSourceFile() throws Exception {
        final javax.tools.JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        org.junit.jupiter.api.Assumptions.assumeTrue(compiler != null, "system Java compiler unavailable");
        final Path bridge = McpStdioBridge.publish(stateDir);
        assertEquals(0, compiler.run(null, null, null, bridge.toString()));
    }

    @Test
    void launchDescriptorTargetsTheCompiledBridgeInsideThePluginClasses() throws Exception {
        final CapturingPluginLogger logger = new CapturingPluginLogger();

        final Optional<dev.turboism.sdk.mcp.McpStdioLaunch> launch = McpStdioBridge.launch(stateDir, logger);

        assertTrue(launch.isPresent());
        final dev.turboism.sdk.mcp.McpStdioLaunch descriptor = launch.orElseThrow();
        final Path expectedLauncher = Path.of(System.getProperty("java.home"), "bin", javaExecutable());
        assertEquals(expectedLauncher.toAbsolutePath().normalize().toString(), descriptor.command());
        assertTrue(Files.isRegularFile(Path.of(descriptor.command())));
        assertEquals(4, descriptor.args().size());
        assertEquals("-cp", descriptor.args().get(0));
        assertTrue(Files.exists(Path.of(descriptor.args().get(1))));
        assertEquals(McpStdioBridge.MAIN_CLASS, descriptor.args().get(2));
        assertEquals(
                stateDir.toAbsolutePath().normalize().toString(),
                descriptor.args().get(3));
        assertTrue(logger.warnings.isEmpty());
    }

    @Test
    void launchCarriesNoTokenMaterialInCommandOrArgs() throws Exception {
        final String token = "ab12cd34".repeat(8);
        Files.writeString(stateDir.resolve(McpAccessToken.FILE_NAME), token + "\n");

        final dev.turboism.sdk.mcp.McpStdioLaunch descriptor =
                McpStdioBridge.launch(stateDir, new CapturingPluginLogger()).orElseThrow();

        assertFalse(descriptor.command().contains(token));
        descriptor.args().forEach(arg -> assertFalse(arg.contains(token)));
        assertFalse(descriptor.toString().contains(token));
    }

    @Test
    void launchFailsClosedAndWarnsOnceWithoutPathsWhenInputsAreMissing() {
        final CapturingPluginLogger logger = new CapturingPluginLogger();
        final Path missing = stateDir.resolve("does-not-exist");

        assertTrue(McpStdioBridge.launch(stateDir, logger, null, temporaryCodeSource())
                .isEmpty());
        assertTrue(McpStdioBridge.launch(stateDir, logger, missing, temporaryCodeSource())
                .isEmpty());
        assertTrue(McpStdioBridge.launch(stateDir, logger, javaLauncher(), null).isEmpty());
        assertTrue(
                McpStdioBridge.launch(stateDir, logger, javaLauncher(), missing).isEmpty());

        assertEquals(4, logger.warnings.size());
        logger.warnings.forEach(warning -> {
            assertFalse(warning.contains(stateDir.toAbsolutePath().toString()));
            assertFalse(warning.contains("mcp.token"));
        });
    }

    @Test
    void launchDescriptorArgsSurviveAPathWithSpaces() throws Exception {
        final Path spacedState = Files.createDirectory(stateDir.resolve("spaced state dir"));
        final Path spacedCodeSource = Files.createDirectory(stateDir.resolve("code source"));
        final Path launcher = javaLauncher();
        org.junit.jupiter.api.Assumptions.assumeTrue(launcher != null, "no JVM launcher in test");

        final dev.turboism.sdk.mcp.McpStdioLaunch descriptor = McpStdioBridge.launch(
                        spacedState, new CapturingPluginLogger(), launcher, spacedCodeSource)
                .orElseThrow();

        assertEquals(
                spacedCodeSource.toAbsolutePath().normalize().toString(),
                descriptor.args().get(1));
        assertEquals(
                spacedState.toAbsolutePath().normalize().toString(),
                descriptor.args().get(3));
    }

    private static Path javaLauncher() {
        final String executable = System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win")
                ? "java.exe"
                : "java";
        final Path launcher = Path.of(System.getProperty("java.home"), "bin", executable);
        return Files.isRegularFile(launcher) ? launcher : null;
    }

    private static String javaExecutable() {
        return System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win")
                ? "java.exe"
                : "java";
    }

    private Path temporaryCodeSource() {
        return stateDir;
    }

    private static final class CapturingPluginLogger implements dev.turboism.sdk.plugin.PluginLogger {
        private final List<String> warnings = new java.util.ArrayList<>();

        @Override
        public void debug(final String message) {}

        @Override
        public void info(final String message) {}

        @Override
        public void warn(final String message) {
            warnings.add(message);
        }

        @Override
        public void error(final String message) {}

        @Override
        public void error(final String message, final Throwable throwable) {}
    }

    @Test
    void clientConfigTargetsThePublishedBridge() {
        final Map<String, Object> config = McpStdioBridge.clientConfig(stateDir);
        assertEquals("java", config.get("command"));
        assertEquals(
                List.of(stateDir.resolve(McpStdioBridge.FILE_NAME)
                        .toAbsolutePath()
                        .toString()),
                config.get("args"));
        final String json = McpStdioBridge.clientConfigJson(stateDir);
        final Map<String, ?> parsed = Json.parseObject(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(config, parsed);
        assertTrue(json.startsWith("{") && json.endsWith("}"));
    }
}
