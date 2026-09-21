package dev.turboism.preview;

import dev.turboism.adapter.host.HostSession;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end lifecycle isolation through the real {@link LocalPluginRuntime}: a plugin whose
 * {@code init()} blocks must not starve an independent plugin or runtime close, the timed-out
 * generation must fence admission, and a worker released after its deadline must not activate.
 */
class PreviewPluginLifecycleIsolationIntegrationTest {

    private static final String BLOCKING_ID = "dev.example.blocking";
    private static final String NORMAL_ID = "dev.example.normal";
    private static final String FAILING_ID = "dev.example.failing-init";
    private static final String ENTERED = "dev.turboism.test.blocking.entered";
    private static final String RELEASE = "dev.turboism.test.blocking.release";
    private static final String RESUMED = "dev.turboism.test.blocking.resumed";
    private static final String LATE_ENABLE = "dev.turboism.test.blocking.late-enable";
    private static final String FENCED = "dev.turboism.test.failing.fenced";
    private static final String SCOPE_CLOSED = "dev.turboism.test.failing.scope-closed";

    private static final PluginLifecyclePolicy SHORT_POLICY = new PluginLifecyclePolicy(
        2,
        16,
        Duration.ofMillis(300),
        Duration.ofSeconds(2),
        Duration.ofSeconds(3),
        Duration.ofMillis(200),
        Duration.ofMillis(40)
    );

    @TempDir
    Path temporary;

    @Test
    void blockingInitIsFencedLateEnableNeverActivatesAndOtherPluginsLoad() throws Exception {
        final Path home = temporary.resolve("home");
        final Path plugins = home.resolve("plugins");
        writePlugin(plugins, "blocking.jar", BLOCKING_ID,
            "dev/example/blocking/BlockingEntrypoint.java",
            "dev.example.blocking.BlockingEntrypoint", blockingSource());
        writePlugin(plugins, "normal.jar", NORMAL_ID,
            "dev/example/normal/NormalEntrypoint.java",
            "dev.example.normal.NormalEntrypoint", normalSource());
        final RuntimeScheduler scheduler = scheduler();
        final HostSession host = new HostSession(Optional::empty);
        clearMarkers();

        try (PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"))) {
            final LocalPluginRuntime runtime = new LocalPluginRuntime(
                home,
                scheduler,
                host.adapterAccess(),
                log,
                (pluginId, phase) -> { },
                SHORT_POLICY
            );
            try {
                final LocalPluginRuntime.LoadReport report = runtime.loadAll();

                assertTrue(
                    awaitMarker(ENTERED, 5),
                    "the blocking plugin's init must have started on the lane"
                );
                assertTrue(
                    report.failures().stream().anyMatch(failure ->
                        failure.pluginId().equals(BLOCKING_ID)),
                    "the timed-out plugin must be reported failed"
                );
                assertTrue(
                    report.loaded().stream().anyMatch(plugin ->
                        plugin.id().equals(NORMAL_ID)
                            && "ENABLED".equals(plugin.state().name())),
                    "an independent plugin must still load while another blocks"
                );
                assertFalse(
                    runtime.loadedPlugins().stream().anyMatch(plugin ->
                        plugin.id().equals(BLOCKING_ID)),
                    "the fenced generation must never publish as loaded"
                );

                // Advisory interruption is not a kill: the blocked worker resumes when the
                // plugin's own condition clears, then hits the lease fence before enable().
                System.setProperty(RELEASE, "1");
                assertTrue(awaitMarker(RESUMED, 5), "the blocked worker must resume");
                Thread.sleep(300);
                assertEquals(
                    null,
                    System.getProperty(LATE_ENABLE),
                    "enable() of a timed-out generation must never run"
                );
                assertFalse(
                    runtime.loadedPlugins().stream().anyMatch(plugin ->
                        plugin.id().equals(BLOCKING_ID)),
                    "late completion must not resurrect a fenced generation"
                );
            } finally {
                runtime.close();
            }
        } finally {
            clearMarkers();
            host.close();
            scheduler.shutdown();
        }
    }

    @Test
    void failedInitFencesPreviouslyAcquiredContextAndClosesScope() throws Exception {
        final Path home = temporary.resolve("home");
        writePlugin(home.resolve("plugins"), "failing.jar", FAILING_ID,
            "dev/example/failing/FailingEntrypoint.java",
            "dev.example.failing.FailingEntrypoint", failingSource());
        final RuntimeScheduler scheduler = scheduler();
        final HostSession host = new HostSession(Optional::empty);
        clearMarkers();

        try (PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"))) {
            final LocalPluginRuntime runtime = new LocalPluginRuntime(
                home,
                scheduler,
                host.adapterAccess(),
                log,
                (pluginId, phase) -> { },
                SHORT_POLICY
            );
            try {
                final LocalPluginRuntime.LoadReport report = runtime.loadAll();

                assertTrue(report.failures().stream().anyMatch(failure ->
                    failure.pluginId().equals(FAILING_ID)
                        && "LOAD_FAILED".equals(failure.code())));
                // The plugin's own background thread keeps calling the context it acquired in
                // init; after the failure fence that handle must deny access.
                assertTrue(
                    awaitMarker(FENCED, 5),
                    "a pre-failure context handle must be fenced after load failure"
                );
                assertEquals("fenced", System.getProperty(FENCED));
                assertTrue(
                    awaitMarker(SCOPE_CLOSED, 5),
                    "the failed generation's scope must still be disposed"
                );
            } finally {
                runtime.close();
            }
        } finally {
            clearMarkers();
            host.close();
            scheduler.shutdown();
        }
    }

    private static void clearMarkers() {
        for (String key : new String[]{
            ENTERED, RELEASE, RESUMED, LATE_ENABLE, FENCED, SCOPE_CLOSED
        }) {
            System.clearProperty(key);
        }
    }

    private static boolean awaitMarker(final String key, final long seconds)
        throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.getProperty(key) == null) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(20);
        }
        return true;
    }

    private static String blockingSource() {
        return """
            package dev.example.blocking;

            import dev.turboism.sdk.plugin.PluginContext;
            import dev.turboism.sdk.plugin.TurboismPlugin;

            public final class BlockingEntrypoint implements TurboismPlugin {
                @Override public void init(PluginContext context) {
                    System.setProperty("%s", "1");
                    while (System.getProperty("%s") == null) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException ignored) {
                            // Swallow the advisory interrupt: plugin code decides when to stop.
                        }
                    }
                    System.setProperty("%s", "1");
                }

                @Override public void enable() {
                    System.setProperty("%s", "1");
                }
            }
            """.formatted(ENTERED, RELEASE, RESUMED, LATE_ENABLE);
    }

    private static String normalSource() {
        return """
            package dev.example.normal;

            import dev.turboism.sdk.plugin.TurboismPlugin;

            public final class NormalEntrypoint implements TurboismPlugin {
            }
            """;
    }

    private static String failingSource() {
        return """
            package dev.example.failing;

            import dev.turboism.sdk.plugin.PluginContext;
            import dev.turboism.sdk.plugin.TurboismPlugin;

            public final class FailingEntrypoint implements TurboismPlugin {
                @Override public void init(PluginContext context) {
                    context.disposableScope().register(() -> {
                        System.setProperty("%s", "closed");
                    });
                    Thread probe = new Thread(() -> {
                        try {
                            for (int i = 0; i < 400; i++) {
                                context.paths().dataDir();
                                Thread.sleep(10);
                            }
                            System.setProperty("%s", "unfenced");
                        } catch (IllegalStateException fenced) {
                            System.setProperty("%s", "fenced");
                        } catch (Throwable other) {
                            System.setProperty(
                                "%s", "other:" + other.getClass().getSimpleName()
                            );
                        }
                    });
                    probe.setDaemon(true);
                    probe.start();
                    throw new IllegalStateException("init failed deliberately");
                }
            }
            """.formatted(SCOPE_CLOSED, FENCED, FENCED, FENCED);
    }

    private void writePlugin(
        final Path pluginDirectory,
        final String jarName,
        final String pluginId,
        final String sourcePath,
        final String entrypoint,
        final String source
    ) throws Exception {
        final Path sourceRoot = temporary.resolve("src-" + jarName);
        final Path classes = temporary.resolve("classes-" + jarName);
        final Path sourceFile = sourceRoot.resolve(sourcePath);
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        Files.createDirectories(classes);
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final int result = compiler.run(
            null,
            null,
            null,
            "-classpath",
            System.getProperty("java.class.path"),
            "-d",
            classes.toString(),
            sourceFile.toString()
        );
        if (result != 0) {
            throw new IllegalStateException("fixture compilation failed: " + jarName);
        }

        Files.createDirectories(pluginDirectory);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(
            pluginDirectory.resolve(jarName)
        ))) {
            try (var paths = Files.walk(classes)) {
                for (Path path : paths.filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder()).toList()) {
                    add(
                        output,
                        classes.relativize(path).toString().replace('\\', '/'),
                        Files.readAllBytes(path)
                    );
                }
            }
            add(
                output,
                "META-INF/turboism/plugin.json",
                descriptor(pluginId, entrypoint).getBytes(StandardCharsets.UTF_8)
            );
            add(output, "META-INF/turboism/i18n/messages.properties", new byte[0]);
        }
    }

    private static String descriptor(final String pluginId, final String entrypoint) {
        return """
            {"format":"turboism.plugin.meta","schemaVersion":2,
            "id":"%s","name":"%s","version":"0.1.0",
            "description":"test","entrypoints":["%s"],
            "turboismApi":"[0.1.0,0.2.0)","authors":[{"name":"Tests"}],
            "license":"Test","website":"https://turboism.dev",
            "resources":[],"i18n":{"baseName":"META-INF/turboism/i18n/messages","locales":[]},
            "dependencies":[],"permissions":[],"capabilities":[],
            "environment":{"requiresCubism":false,"ui":"none"}}
            """.formatted(pluginId, pluginId, entrypoint);
    }

    private static RuntimeScheduler scheduler() {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 16, ignored -> { }, Clock.systemUTC()),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
    }

    private static void add(
        final JarOutputStream output,
        final String name,
        final byte[] content
    ) throws Exception {
        output.putNextEntry(new JarEntry(name));
        output.write(content);
        output.closeEntry();
    }
}
