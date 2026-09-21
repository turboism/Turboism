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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A required {@code before} dependency lets the declarer load ahead of its target; when the
 * target then fails at runtime, the already-live dependents must be fenced and unloaded through
 * the normal shutdown path, dependents before the members they require.
 */
class PreviewPluginDependencyRollbackIntegrationTest {

    private static final String CONSUMER_ID = "dev.example.dependent-consumer";
    private static final String TOP_CONSUMER_ID = "dev.example.top-consumer";
    private static final String PROVIDER_ID = "dev.example.failing-provider";
    private static final String INIT_PROPERTY = CONSUMER_ID + ".initialized";
    private static final String DISABLE_ORDER_PROPERTY = "dev.example.disable-order";

    @TempDir
    Path temporary;

    @Test
    void requiredBeforeDependentsAreUnloadedDependentsFirstWhenProviderFails() throws Exception {
        final Path home = temporary.resolve("home");
        final Path plugins = home.resolve("plugins");
        writePlugin(
            plugins.resolve("00-consumer.jar"), CONSUMER_ID,
            "dev.example.consumer.ConsumerPlugin", consumerEntrypoint(CONSUMER_ID, "ConsumerPlugin"),
            """
            {"id":"%s","version":"[0.1.0,0.2.0)","type":"required","ordering":"before"}
            """.formatted(PROVIDER_ID));
        writePlugin(
            plugins.resolve("05-top.jar"), TOP_CONSUMER_ID,
            "dev.example.top.TopConsumerPlugin", consumerEntrypoint(TOP_CONSUMER_ID, "TopConsumerPlugin"),
            """
            {"id":"%s","version":"[0.1.0,0.2.0)","type":"required","ordering":"after"}
            """.formatted(CONSUMER_ID));
        writePlugin(
            plugins.resolve("10-provider.jar"), PROVIDER_ID,
            "dev.example.provider.ProviderPlugin", providerEntrypoint(), null);

        final RuntimeScheduler scheduler = scheduler();
        final HostSession host = new HostSession(Optional::empty);
        System.clearProperty(INIT_PROPERTY);
        System.clearProperty(DISABLE_ORDER_PROPERTY);

        try (PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"))) {
            final LocalPluginRuntime runtime = new LocalPluginRuntime(
                home, scheduler, host.adapterAccess(), log
            );
            try {
                final LocalPluginRuntime.LoadReport report = runtime.loadAll();

                assertEquals("init", System.getProperty(INIT_PROPERTY),
                    "before ordering must let the consumer initialize ahead of the provider");
                assertEquals(
                    TOP_CONSUMER_ID + ";" + CONSUMER_ID + ";",
                    System.getProperty(DISABLE_ORDER_PROPERTY),
                    "dependents must unload before the members they require"
                );

                final List<LocalPluginRuntime.LoadedPluginSummary> external = report.loaded().stream()
                    .filter(summary -> !summary.id().equals("turboism.core"))
                    .toList();
                assertTrue(external.isEmpty(),
                    "no member of the failed closure may stay loaded: " + external);

                for (String dependentId : List.of(CONSUMER_ID, TOP_CONSUMER_ID)) {
                    final LocalPluginRuntime.PluginFailure failure = report.failures().stream()
                        .filter(entry -> entry.pluginId().equals(dependentId))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(
                            "missing rollback failure for " + dependentId + ": " + report.failures()));
                    assertEquals("DEPENDENCY_LOAD_FAILED", failure.code());
                }
                assertTrue(report.failures().stream().anyMatch(failure ->
                    failure.pluginId().equals(PROVIDER_ID)));
                assertTrue(report.dependencyCycles().isEmpty());

                assertFalse(runtime.loadedPlugins().stream().anyMatch(plugin ->
                    plugin.id().equals(CONSUMER_ID) || plugin.id().equals(TOP_CONSUMER_ID)));
            } finally {
                runtime.close();
            }
        } finally {
            System.clearProperty(INIT_PROPERTY);
            System.clearProperty(DISABLE_ORDER_PROPERTY);
            host.close();
            scheduler.shutdown();
        }
    }

    private static String consumerEntrypoint(final String pluginId, final String className) {
        final String packageName = className.equals("TopConsumerPlugin")
            ? "dev.example.top"
            : "dev.example.consumer";
        return """
            package %s;

            import dev.turboism.sdk.plugin.PluginContext;
            import dev.turboism.sdk.plugin.TurboismPlugin;

            public final class %s implements TurboismPlugin {
                @Override public void init(PluginContext context) {
                    if ("%s".equals("%s")) {
                        System.setProperty("%s", "init");
                    }
                }

                @Override public void disable() {
                    String order = System.getProperty("%s", "");
                    System.setProperty("%s", order + "%s;");
                }
            }
            """.formatted(
                packageName, className,
                CONSUMER_ID, pluginId, INIT_PROPERTY,
                DISABLE_ORDER_PROPERTY, DISABLE_ORDER_PROPERTY, pluginId
            );
    }

    private static String providerEntrypoint() {
        return """
            package dev.example.provider;

            import dev.turboism.sdk.plugin.TurboismPlugin;

            public final class ProviderPlugin implements TurboismPlugin {
                @Override public void enable() {
                    throw new IllegalStateException("provider enable failed on purpose");
                }
            }
            """;
    }

    private void writePlugin(
        final Path jar,
        final String id,
        final String className,
        final String entrypointSource,
        final String dependency
    ) throws Exception {
        final String baseName = id.substring(id.lastIndexOf('-') + 1);
        final Path sourceRoot = temporary.resolve("source-" + baseName);
        final Path classes = temporary.resolve("classes-" + baseName);
        final Path source = sourceRoot.resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, entrypointSource, StandardCharsets.UTF_8);
        Files.createDirectories(classes);
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final int result = compiler.run(
            null, null, null,
            "-classpath", System.getProperty("java.class.path"),
            "-d", classes.toString(),
            source.toString()
        );
        if (result != 0) {
            throw new IllegalStateException("fixture compilation failed for " + id);
        }

        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            try (var paths = Files.walk(classes)) {
                for (Path path : paths.filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder()).toList()) {
                    add(output, classes.relativize(path).toString().replace('\\', '/'),
                        Files.readAllBytes(path));
                }
            }
            add(output, "META-INF/turboism/plugin.json",
                descriptor(id, className, dependency).getBytes(StandardCharsets.UTF_8));
            add(output, "META-INF/turboism/i18n/messages.properties", new byte[0]);
        }
    }

    private static String descriptor(
        final String id,
        final String entrypoint,
        final String dependency
    ) {
        final String dependencies = dependency == null ? "[]" : "[" + dependency + "]";
        return """
            {"format":"turboism.plugin.meta","schemaVersion":2,
            "id":"%s","name":"%s","version":"0.1.0",
            "description":"test","entrypoints":["%s"],
            "turboismApi":"[0.1.0,0.2.0)","authors":[{"name":"Tests"}],
            "license":"Test","website":"https://turboism.dev",
            "resources":[],"i18n":{"baseName":"META-INF/turboism/i18n/messages","locales":[]},
            "dependencies":%s,"permissions":[],"capabilities":[],
            "environment":{"requiresCubism":false,"ui":"none"}}
            """.formatted(id, id, entrypoint, dependencies);
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
