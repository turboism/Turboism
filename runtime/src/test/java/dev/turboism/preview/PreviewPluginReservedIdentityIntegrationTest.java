package dev.turboism.preview;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.adapter.host.HostSession;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.preview.report.PreviewReportSnapshotFactory;
import dev.turboism.preview.report.PreviewReportType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewPluginReservedIdentityIntegrationTest {
    private static final String CORE_ID = "turboism.core";
    private static final String NORMAL_ID = "dev.example.normal";
    private static final String CORE_MARKER = "dev.turboism.test.external-core-loaded";
    private static final String NORMAL_MARKER = "dev.turboism.test.normal-loaded";

    @TempDir
    Path temporary;

    @Test
    void discoveryRejectsExternalCoreBeforeResolutionOrEntrypointLoading() throws Exception {
        final Path home = temporary.resolve("home");
        final Path plugins = home.resolve("plugins");
        writePlugin(plugins, temporary.resolve("core"), "spoof-core.jar", CORE_ID, CORE_MARKER);
        final List<LocalPluginRuntime.PluginFailure> failures = new ArrayList<>();

        try (PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"))) {
            final var candidates = new PreviewPluginDiscovery(plugins, log).discover(failures);

            assertFalse(candidates.containsKey(CORE_ID));
            assertEquals(1, failures.size());
            assertEquals(CORE_ID, failures.get(0).pluginId());
            assertEquals("PLUGIN_RESERVED_ID", failures.get(0).code());
            assertFalse(Boolean.getBoolean(CORE_MARKER));
        } finally {
            System.clearProperty(CORE_MARKER);
        }
    }

    @Test
    void runtimeKeepsBuiltinCoreLoadsNormalPluginAndReportsSpoofAsNotAdmitted() throws Exception {
        final Path home = temporary.resolve("runtime-home");
        final Path plugins = home.resolve("plugins");
        writePlugin(plugins, temporary.resolve("runtime-core"), "spoof-core.jar", CORE_ID, CORE_MARKER);
        writePlugin(plugins, temporary.resolve("normal"), "normal.jar", NORMAL_ID, NORMAL_MARKER);
        final RuntimeScheduler scheduler = scheduler();
        final HostSession host = new HostSession(Optional::empty);

        try (PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"))) {
            final LocalPluginRuntime runtime = new LocalPluginRuntime(home, scheduler, host.adapterAccess(), log);
            try {
                final LocalPluginRuntime.LoadReport report = runtime.loadAll();
                assertTrue(report.loaded().stream().anyMatch(plugin ->
                    plugin.id().equals(CORE_ID) && plugin.state().name().equals("ENABLED")));
                assertTrue(report.loaded().stream().anyMatch(plugin ->
                    plugin.id().equals(NORMAL_ID) && plugin.state().name().equals("ENABLED")));
                assertEquals(1, report.failures().size());
                assertEquals("PLUGIN_RESERVED_ID", report.failures().get(0).code());
                assertFalse(Boolean.getBoolean(CORE_MARKER));
                assertTrue(Boolean.getBoolean(NORMAL_MARKER));

                final JsonNode pluginsReport = PreviewReportSnapshotFactory.create(
                    "runtime-reserved-id-test",
                    Instant.parse("2026-07-31T00:00:00Z"),
                    home,
                    HostSession.State.SAFE_MODE,
                    null,
                    null,
                    report,
                    report.loaded(),
                    false
                ).get(PreviewReportType.PLUGIN_LOAD).path("payload").path("plugins");
                final JsonNode spoof = java.util.stream.StreamSupport.stream(pluginsReport.spliterator(), false)
                    .filter(plugin -> plugin.path("artifactRelativePath").asText().endsWith("spoof-core.jar"))
                    .findFirst().orElseThrow();
                assertEquals("NOT_DISCOVERED", spoof.path("discoveryState").textValue());
                assertEquals("NOT_EVALUATED", spoof.path("dependencyState").textValue());
                assertEquals("PLUGIN_RESERVED_ID", spoof.path("failures").get(0).path("code").textValue());
                assertEquals("discovery", spoof.path("failures").get(0).path("phase").textValue());
            } finally {
                runtime.close();
            }
        } finally {
            host.close();
            scheduler.shutdown();
            System.clearProperty(CORE_MARKER);
            System.clearProperty(NORMAL_MARKER);
        }
    }

    private static RuntimeScheduler scheduler() {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 16, ignored -> { }, Clock.systemUTC()),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
    }

    private static void writePlugin(
        final Path plugins,
        final Path work,
        final String filename,
        final String id,
        final String marker
    ) throws Exception {
        final String className = "dev.example.Fixture" + UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8))
            .toString().replace("-", "");
        final Path source = work.resolve("source/" + className.replace('.', '/') + ".java");
        final Path classes = work.resolve("classes");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package dev.example;
            import dev.turboism.sdk.plugin.TurboismPlugin;
            public final class %s implements TurboismPlugin {
                static { System.setProperty("%s", "true"); }
            }
            """.formatted(className.substring(className.lastIndexOf('.') + 1), marker), StandardCharsets.UTF_8);
        Files.createDirectories(classes);
        final int compiled = ToolProvider.getSystemJavaCompiler().run(
            null, null, null,
            "-classpath", System.getProperty("java.class.path"),
            "-d", classes.toString(),
            source.toString()
        );
        if (compiled != 0) throw new IllegalStateException("fixture compilation failed");
        Files.createDirectories(plugins);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(plugins.resolve(filename)))) {
            try (var files = Files.walk(classes)) {
                for (Path file : files.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList()) {
                    add(output, classes.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
                }
            }
            add(output, "META-INF/turboism/plugin.json", descriptor(id, className).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String descriptor(final String id, final String entrypoint) {
        return """
            {"format":"turboism.plugin.meta","schemaVersion":2,"id":"%s","name":"Fixture","version":"0.1.0",
            "description":"test","entrypoints":["%s"],"turboismApi":"[0.1.0,0.2.0)",
            "authors":[{"name":"Tests"}],"license":"Test","website":"https://turboism.dev","resources":[],
            "i18n":{"baseName":"META-INF/turboism/i18n/messages","locales":[]},"dependencies":[],"permissions":[],
            "capabilities":[],"environment":{"requiresCubism":false,"ui":"none"}}
            """.formatted(id, entrypoint);
    }

    private static void add(final JarOutputStream output, final String name, final byte[] bytes) throws Exception {
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }
}
