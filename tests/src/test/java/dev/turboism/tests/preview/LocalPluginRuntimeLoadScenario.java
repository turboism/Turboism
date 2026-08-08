package dev.turboism.tests.preview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.bootstrap.HostRuntimeIngress;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.config.RuntimeConfigRepository;
import dev.turboism.preview.LocalPluginRuntime;
import dev.turboism.preview.PreviewLog;

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

/** Characterizes discovery, descriptor parsing, classloading, and dependency isolation. */
final class LocalPluginRuntimeLoadScenario {

    private LocalPluginRuntimeLoadScenario() {
    }

    static void verify(final Path temporaryDirectory) throws Exception {
        final Path home = temporaryDirectory.resolve("preview home");
        writePluginFixtures(home.resolve("plugins"));
        loadAndAssert(home);
        assertTrue(Files.readString(home.resolve("logs/turboism.log"))
            .contains("Plugin unloaded with state UNLOADED"));
    }

    private static void writePluginFixtures(final Path plugins) throws IOException {
        Files.createDirectories(plugins);
        final Path inspectorJar = projectInspectorJar();
        Files.copy(inspectorJar, plugins.resolve("project-inspector.jar"));
        writeEmptyJar(plugins.resolve("00-broken.jar"));
        rewritePluginJar(
            inspectorJar, plugins.resolve("10-failing-base.jar"),
            "dev.example.base", "dev.example.MissingPlugin", null
        );
        rewritePluginJar(
            inspectorJar, plugins.resolve("20-dependent.jar"),
            "dev.example.dependent", "dev.turboism.plugin.projectinspector.ProjectInspectorPlugin",
            "dev.example.base"
        );
    }

    private static void loadAndAssert(final Path home) throws Exception {
        new RuntimeConfigRepository(home, ignored -> {}).update(config -> {
            config.put("logLevel", "DEBUG");
            return config;
        });
        final PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"));
        final RuntimeScheduler scheduler = scheduler();
        final HostRuntimeIngress hostIngress = new HostRuntimeIngress();
        final LocalPluginRuntime runtime = new LocalPluginRuntime(
            home, scheduler, hostIngress.adapterAccess(), log
        );
        try {
            assertLoadReport(runtime);
            assertRuntimeLog(home);
        } finally {
            runtime.close();
            hostIngress.close();
            scheduler.shutdown();
            log.close();
        }
    }

    private static void assertLoadReport(final LocalPluginRuntime runtime) {
        final LocalPluginRuntime.LoadReport report = runtime.loadAll();
        assertEquals(2, report.loaded().size());
        assertEquals("dev.turboism.plugin.project-inspector", report.loaded().stream().filter(plugin -> !plugin.id().equals("turboism.core")).findFirst().orElseThrow().id());
        assertEquals("ENABLED", report.loaded().stream().filter(plugin -> !plugin.id().equals("turboism.core")).findFirst().orElseThrow().state().name());
        assertEquals(3, report.failures().size());
        final Map<String, LocalPluginRuntime.PluginFailure> failuresByCode = report.failures().stream()
            .collect(Collectors.toMap(LocalPluginRuntime.PluginFailure::code, Function.identity()));
        assertTrue(failuresByCode.containsKey("PLUGIN_DESCRIPTOR_MISSING"));
        assertTrue(
            failuresByCode.containsKey("PLUGIN_ENTRYPOINT_CLASS_MISSING"),
            report.failures().toString()
        );
        assertEquals(
            "dev.example.dependent",
            failuresByCode.get("DEPENDENCY_FAILED").pluginId(),
            report.failures().toString()
        );
        assertTrue(report.dependencyCycles().isEmpty());
    }

    private static void assertRuntimeLog(final Path home) throws IOException {
        final String runtimeLog = Files.readString(home.resolve("logs/turboism.log"));
        assertTrue(runtimeLog.contains("Loaded plugin Project Inspector"));
        assertTrue(runtimeLog.contains("Localization active locale="));
    }

    private static RuntimeScheduler scheduler() {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 16, ignored -> { }, Clock.systemUTC()),
            SidecarDispatcher.noop(), ignored -> { }
        );
    }

    private static Path projectInspectorJar() throws IOException {
        final Path libs = Path.of(System.getProperty("projectInspectorBuildDir")).resolve("libs");
        try (var files = Files.list(libs)) {
            return files.filter(Files::isRegularFile)
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
            for (JarEntry entry : input.stream().toList()) {
                copyEntry(input, output, entry, mapper, id, entrypoint, requiredDependency);
            }
        }
    }

    private static void copyEntry(
        final JarFile input,
        final JarOutputStream output,
        final JarEntry sourceEntry,
        final ObjectMapper mapper,
        final String id,
        final String entrypoint,
        final String requiredDependency
    ) throws IOException {
        output.putNextEntry(new JarEntry(sourceEntry.getName()));
        if (!sourceEntry.isDirectory()) {
            final byte[] bytes = input.getInputStream(sourceEntry).readAllBytes();
            output.write(rewriteDescriptor(bytes, sourceEntry.getName(), mapper, id, entrypoint, requiredDependency));
        }
        output.closeEntry();
    }

    private static byte[] rewriteDescriptor(
        final byte[] bytes,
        final String entryName,
        final ObjectMapper mapper,
        final String id,
        final String entrypoint,
        final String requiredDependency
    ) throws IOException {
        if (!entryName.equals("META-INF/turboism/plugin.json")) {
            return bytes;
        }
        final ObjectNode descriptor = (ObjectNode) mapper.readTree(bytes);
        descriptor.put("id", id);
        descriptor.put("name", id);
        descriptor.putArray("entrypoints").add(entrypoint);
        descriptor.set("dependencies", dependencies(mapper, requiredDependency));
        return mapper.writeValueAsBytes(descriptor);
    }

    private static ArrayNode dependencies(final ObjectMapper mapper, final String requiredDependency) {
        final ArrayNode dependencies = mapper.createArrayNode();
        if (requiredDependency != null) {
            final ObjectNode dependency = dependencies.addObject();
            dependency.put("id", requiredDependency);
            dependency.put("type", "required");
            dependency.put("version", "[0.1.0,0.2.0)");
            dependency.put("ordering", "after");
        }
        return dependencies;
    }

    private static void writeEmptyJar(final Path target) throws IOException {
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(target))) {
            // Intentionally empty: discovery must report it without blocking the valid plugin.
        }
    }
}
