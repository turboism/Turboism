package dev.turboism.preview;

import dev.turboism.adapter.host.HostSession;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
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

/**
 * Retired fake plugin ids must never be admitted on any load path: a valid
 * JAR descriptor carrying a retired id is rejected by the shared
 * {@link dev.turboism.core.plugin.PluginJarContract} before entrypoint
 * loading, on every distribution path (installer payloads, preview discovery,
 * manual/NSIS leftovers, renamed JARs). The retained clipmask-viewer
 * successor id stays admitted.
 */
class PreviewPluginRetiredIdentityIntegrationTest {

    private static final String RETIRED_ID = "dev.turboism.plugin.logfilter";
    private static final String RETIRED_MARKER = "dev.turboism.test.retired-loaded";
    private static final String SUCCESSOR_ID = "dev.turboism.plugin.clipmask-viewer";
    private static final String SUCCESSOR_MARKER = "dev.turboism.test.successor-loaded";

    @TempDir
    Path temporary;

    @Test
    void discoveryRejectsRetiredJarBeforeEntrypointLoading() throws Exception {
        final Path home = temporary.resolve("home");
        final Path plugins = home.resolve("plugins");
        // canonical and renamed filenames must both be denied by embedded id
        writePlugin(plugins, temporary.resolve("canonical"), "log-filter.jar", RETIRED_ID, RETIRED_MARKER);
        writePlugin(plugins, temporary.resolve("renamed"), "renamed-archive.jar", RETIRED_ID, RETIRED_MARKER);
        writePlugin(plugins, temporary.resolve("successor"), "successor.jar", SUCCESSOR_ID, SUCCESSOR_MARKER);
        final List<LocalPluginRuntime.PluginFailure> failures = new ArrayList<>();

        try (PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"))) {
            final var candidates = new PreviewPluginDiscovery(plugins, log).discover(failures);

            assertFalse(candidates.containsKey(RETIRED_ID));
            assertEquals(2, failures.size());
            for (LocalPluginRuntime.PluginFailure failure : failures) {
                assertEquals(RETIRED_ID, failure.pluginId());
                assertEquals("PLUGIN_RETIRED_ID", failure.code());
            }
            assertTrue(candidates.containsKey(SUCCESSOR_ID));
            // Discovery never loads entrypoint classes; denial means the
            // retired marker stays unset here and in the load test below.
            assertFalse(Boolean.getBoolean(RETIRED_MARKER));
        } finally {
            System.clearProperty(RETIRED_MARKER);
            System.clearProperty(SUCCESSOR_MARKER);
        }
    }

    @Test
    void runtimeLoadDeniesRetiredJarAndAdmitsRetainedSuccessor() throws Exception {
        final Path home = temporary.resolve("runtime-home");
        final Path plugins = home.resolve("plugins");
        writePlugin(plugins, temporary.resolve("retired"), "renamed-retired.jar", RETIRED_ID, RETIRED_MARKER);
        writePlugin(plugins, temporary.resolve("successor"), "clipmask-viewer.jar", SUCCESSOR_ID, SUCCESSOR_MARKER);
        final RuntimeScheduler scheduler = scheduler();
        final HostSession host = new HostSession(Optional::empty);

        try (PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"))) {
            final LocalPluginRuntime runtime = new LocalPluginRuntime(home, scheduler, host.adapterAccess(), log);
            try {
                final LocalPluginRuntime.LoadReport report = runtime.loadAll();
                assertFalse(report.loaded().stream().anyMatch(plugin -> plugin.id().equals(RETIRED_ID)));
                assertTrue(report.loaded().stream().anyMatch(plugin -> plugin.id().equals(SUCCESSOR_ID)));
                assertTrue(report.failures().stream().anyMatch(failure ->
                    failure.pluginId().equals(RETIRED_ID)
                        && "PLUGIN_RETIRED_ID".equals(failure.code())));
                assertFalse(Boolean.getBoolean(RETIRED_MARKER));
                assertTrue(Boolean.getBoolean(SUCCESSOR_MARKER));
            } finally {
                runtime.close();
            }
        } finally {
            host.close();
            scheduler.shutdown();
            System.clearProperty(RETIRED_MARKER);
            System.clearProperty(SUCCESSOR_MARKER);
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
            add(output, "META-INF/turboism/i18n/messages.properties",
                "probe=fixture\n".getBytes(StandardCharsets.UTF_8));
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
