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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class PreviewPluginLoaderRollbackContextClassLoaderIntegrationTest {

    private static final String PLUGIN_ID = "dev.example.rollback-context-loader";
    private static final String DISABLE_PROPERTY = PLUGIN_ID + ".disable-resource";
    private static final String SHUTDOWN_PROPERTY = PLUGIN_ID + ".shutdown-resource";
    private static final String RESOURCE_VALUE = "plugin-owned-resource";

    @TempDir
    Path temporary;

    @Test
    void failedEnableRollbackUsesPluginContextLoaderAndReleasesPluginJar() throws Exception {
        final Path home = temporary.resolve("home");
        final Path pluginJar = writePlugin(home.resolve("plugins"));
        final RuntimeScheduler scheduler = scheduler();
        final HostSession host = new HostSession(Optional::empty);
        final ClassLoader originalContextLoader = Thread.currentThread().getContextClassLoader();
        System.clearProperty(DISABLE_PROPERTY);
        System.clearProperty(SHUTDOWN_PROPERTY);

        try (PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"))) {
            final LocalPluginRuntime runtime = new LocalPluginRuntime(
                home, scheduler, host.adapterAccess(), log
            );
            try {
                final LocalPluginRuntime.LoadReport report = runtime.loadAll();

                assertEquals(1, report.failures().size());
                assertEquals(PLUGIN_ID, report.failures().get(0).pluginId());
                assertEquals("ENABLE_FAILED", report.failures().get(0).code());
                assertEquals(RESOURCE_VALUE, System.getProperty(DISABLE_PROPERTY));
                assertEquals(RESOURCE_VALUE, System.getProperty(SHUTDOWN_PROPERTY));
                assertSame(originalContextLoader, Thread.currentThread().getContextClassLoader());
                assertFalse(runtime.loadedPlugins().stream().anyMatch(plugin ->
                    plugin.id().equals(PLUGIN_ID)
                ));

                Files.delete(pluginJar);
                assertFalse(Files.exists(pluginJar));
            } finally {
                runtime.close();
            }
        } finally {
            System.clearProperty(DISABLE_PROPERTY);
            System.clearProperty(SHUTDOWN_PROPERTY);
            host.close();
            scheduler.shutdown();
        }
    }

    private Path writePlugin(final Path pluginDirectory) throws Exception {
        final Path sourceRoot = temporary.resolve("rollback-source");
        final Path classes = temporary.resolve("rollback-classes");
        final Path source = sourceRoot.resolve(
            "dev/example/rollback/RollbackEntrypoint.java"
        );
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package dev.example.rollback;

            import dev.turboism.sdk.plugin.TurboismPlugin;
            import java.io.InputStream;
            import java.nio.charset.StandardCharsets;

            public final class RollbackEntrypoint implements TurboismPlugin {
                private static void readOwnedResource(String property) throws Exception {
                    ClassLoader loader = Thread.currentThread().getContextClassLoader();
                    try (InputStream input = loader.getResourceAsStream(
                        "dev/example/rollback/rollback-owned.txt"
                    )) {
                        if (input == null) {
                            throw new IllegalStateException("plugin-owned resource not visible");
                        }
                        System.setProperty(
                            property,
                            new String(input.readAllBytes(), StandardCharsets.UTF_8).trim()
                        );
                    }
                }

                @Override public void disable() throws Exception {
                    readOwnedResource("%s");
                }

                @Override public void shutdown() throws Exception {
                    readOwnedResource("%s");
                }

                public static final class FailingEntrypoint implements TurboismPlugin {
                    @Override public void enable() {
                        throw new IllegalStateException("later entrypoint enable failed");
                    }
                }
            }
            """.formatted(DISABLE_PROPERTY, SHUTDOWN_PROPERTY), StandardCharsets.UTF_8);
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
            source.toString()
        );
        if (result != 0) {
            throw new IllegalStateException("fixture compilation failed");
        }

        Files.createDirectories(pluginDirectory);
        final Path jar = pluginDirectory.resolve("rollback-context-loader.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
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
                "dev/example/rollback/rollback-owned.txt",
                (RESOURCE_VALUE + "\n").getBytes(StandardCharsets.UTF_8)
            );
            add(
                output,
                "META-INF/turboism/plugin.json",
                descriptor().getBytes(StandardCharsets.UTF_8)
            );
            add(output, "META-INF/turboism/i18n/messages.properties", new byte[0]);
        }
        return jar;
    }

    private static String descriptor() {
        return """
            {"format":"turboism.plugin.meta","schemaVersion":2,
            "id":"%s","name":"Rollback Context Loader","version":"0.1.0",
            "description":"test","entrypoints":[
              "dev.example.rollback.RollbackEntrypoint",
              "dev.example.rollback.RollbackEntrypoint$FailingEntrypoint"
            ],"turboismApi":"[0.1.0,0.2.0)","authors":[{"name":"Tests"}],
            "license":"Test","website":"https://turboism.dev",
            "resources":["dev/example/rollback/"],
            "i18n":{"baseName":"META-INF/turboism/i18n/messages","locales":[]},
            "dependencies":[],"permissions":[],"capabilities":[],
            "environment":{"requiresCubism":false,"ui":"none"}}
            """.formatted(PLUGIN_ID);
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
