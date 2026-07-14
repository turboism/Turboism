package dev.turboism.tests.preview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.bootstrap.HostRuntimeIngress;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.preview.LocalPluginRuntime;
import dev.turboism.preview.PreviewLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPluginRuntimeIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsARealPluginJarAndIsolatesAnInvalidNeighbor() throws Exception {
        final Path home = temporaryDirectory.resolve("preview home");
        final Path plugins = home.resolve("plugins");
        Files.createDirectories(plugins);
        final Path inspectorJar = projectInspectorJar();
        Files.copy(inspectorJar, plugins.resolve("project-inspector.jar"));
        writeEmptyJar(plugins.resolve("00-broken.jar"));
        rewritePluginJar(
            inspectorJar,
            plugins.resolve("10-failing-base.jar"),
            "dev.example.base",
            "dev.example.MissingPlugin",
            null
        );
        rewritePluginJar(
            inspectorJar,
            plugins.resolve("20-dependent.jar"),
            "dev.example.dependent",
            "dev.turboism.plugin.projectinspector.ProjectInspectorPlugin",
            "dev.example.base"
        );

        final PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"));
        final RuntimeScheduler scheduler = scheduler();
        final HostRuntimeIngress hostIngress = new HostRuntimeIngress();
        final LocalPluginRuntime runtime = new LocalPluginRuntime(
            home,
            scheduler,
            hostIngress.adapterAccess(),
            log
        );
        try {
            final LocalPluginRuntime.LoadReport report = runtime.loadAll();

            assertEquals(1, report.loaded().size());
            assertEquals("dev.turboism.plugin.project-inspector", report.loaded().get(0).id());
            assertEquals("ENABLED", report.loaded().get(0).state().name());
            assertEquals(3, report.failures().size());
            final Map<String, LocalPluginRuntime.PluginFailure> failuresByCode = report.failures().stream()
                .collect(Collectors.toMap(LocalPluginRuntime.PluginFailure::code, Function.identity()));
            assertTrue(failuresByCode.containsKey("PLUGIN_DESCRIPTOR_MISSING"));
            assertTrue(failuresByCode.containsKey("LOAD_FAILED"));
            assertEquals(
                "dev.example.dependent",
                failuresByCode.get("DEPENDENCY_LOAD_FAILED").pluginId()
            );
            assertTrue(report.dependencyCycles().isEmpty());
            assertTrue(Files.readString(home.resolve("logs/turboism.log")).contains("Loaded plugin Project Inspector"));
        } finally {
            runtime.close();
            hostIngress.close();
            scheduler.shutdown();
            log.close();
        }

        assertTrue(Files.readString(home.resolve("logs/turboism.log")).contains("Plugin unloaded with state UNLOADED"));
    }

    private static RuntimeScheduler scheduler() {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginExecutorRegistry(1, 16, ignored -> { }, Clock.systemUTC()),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
    }

    private static Path projectInspectorJar() throws IOException {
        final Path libs = Path.of(System.getProperty("projectInspectorBuildDir")).resolve("libs");
        try (var files = Files.list(libs)) {
            return files
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".jar"))
                .min(Comparator.comparing(path -> path.getFileName().toString()))
                .orElseThrow(() -> new IOException("Project Inspector JAR was not built under " + libs));
        }
    }

    private static void rewritePluginJar(
        final Path source,
        final Path target,
        final String id,
        final String entrypoint,
        final String requiredDependency
    ) throws IOException {
        final ObjectMapper mapper = new ObjectMapper();
        try (JarFile input = new JarFile(source.toFile());
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(target))) {
            final var entries = input.stream().toList();
            for (JarEntry sourceEntry : entries) {
                final JarEntry targetEntry = new JarEntry(sourceEntry.getName());
                output.putNextEntry(targetEntry);
                if (!sourceEntry.isDirectory()) {
                    byte[] bytes = input.getInputStream(sourceEntry).readAllBytes();
                    if (sourceEntry.getName().equals("META-INF/turboism/plugin.json")) {
                        final ObjectNode descriptor = (ObjectNode) mapper.readTree(bytes);
                        descriptor.put("id", id);
                        descriptor.put("name", id);
                        ((ObjectNode) descriptor.get("entrypoints")).put("plugin", entrypoint);
                        final ArrayNode dependencies = mapper.createArrayNode();
                        if (requiredDependency != null) {
                            final ObjectNode dependency = mapper.createObjectNode();
                            dependency.put("id", requiredDependency);
                            dependency.put("type", "required");
                            dependency.put("version", "[0.1.0,0.2.0)");
                            dependency.put("ordering", "after");
                            dependencies.add(dependency);
                        }
                        descriptor.set("dependencies", dependencies);
                        bytes = mapper.writeValueAsBytes(descriptor);
                    }
                    output.write(bytes);
                }
                output.closeEntry();
            }
        }
    }

    private static void writeEmptyJar(final Path target) throws IOException {
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(target))) {
            // Intentionally empty: discovery must report it without blocking the valid plugin.
        }
    }
}
