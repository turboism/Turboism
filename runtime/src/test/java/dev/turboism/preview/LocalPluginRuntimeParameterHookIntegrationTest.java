package dev.turboism.preview;

import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.host.HostSession;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.Parameter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalPluginRuntimeParameterHookIntegrationTest {

    @TempDir
    Path temporary;

    @Test
    void loadedCubismEntrypointsAreDiscoveredAndRemovedBeforeRuntimeCloseCompletes() throws Exception {
        final Path pluginDir = temporary.resolve("plugins");
        Files.createDirectories(pluginDir);
        PreviewParameterHookPluginJarFixture.write(pluginDir, temporary);
        final RuntimeScheduler scheduler = scheduler();
        final HostSession host = new HostSession(Optional::empty);
        final ParameterLifecycleCoordinator lifecycle = new ParameterLifecycleCoordinator();
        System.clearProperty(PreviewParameterHookPluginJarFixture.EVENT_PROPERTY);
        try (PreviewLog log = new PreviewLog(temporary.resolve("logs/turboism.log"))) {
            final LocalPluginRuntime runtime = new LocalPluginRuntime(
                temporary, scheduler, host.adapterAccess(), log, lifecycle
            );
            runtime.loadAll();
            final MutableParameter parameter = new MutableParameter();

            lifecycle.setValue(parameter, 2.0F, value -> parameter.value = value);
            lifecycle.awaitIdle();
            assertEquals(2.0F, parameter.value);
            assertEquals(
                List.of("on", "after"),
                PreviewParameterHookPluginJarFixture.events()
            );

            runtime.close();
            System.clearProperty(PreviewParameterHookPluginJarFixture.EVENT_PROPERTY);
            lifecycle.setValue(parameter, 3.0F, value -> parameter.value = value);
            lifecycle.awaitIdle();
            assertEquals(List.of(), PreviewParameterHookPluginJarFixture.events());
        } finally {
            lifecycle.close();
            host.close();
            scheduler.shutdown();
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

    private static final class MutableParameter implements Parameter {
        private float value;
        @Override public ParameterId id() { return new ParameterId("ParamA"); }
        @Override public float getValue() { return value; }
        @Override public float getMinimumValue() { return -1.0F; }
        @Override public float getMaximumValue() { return 1.0F; }
        @Override public float getDefaultValue() { return 0.0F; }
        @Override public void setValue(final float value) { this.value = value; }
    }

    static final class PreviewParameterHookPluginJarFixture {
        static final String EVENT_PROPERTY =
            "dev.turboism.preview.parameter-hook-events";

        static void write(final Path pluginDir, final Path temporary) throws Exception {
            final Path sourceRoot = temporary.resolve("parameter-hook-source");
            final Path classes = temporary.resolve("parameter-hook-classes");
            final Path source = sourceRoot.resolve("dev/example/hooks/HookPlugin.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, """
                package dev.example.hooks;

                import dev.turboism.sdk.cubism.CubismPlugin;
                import dev.turboism.sdk.cubism.model.Parameter;

                public final class HookPlugin implements CubismPlugin {
                    private static void record(String value) {
                        String previous = System.getProperty("%s", "");
                        System.setProperty("%s", previous + value + ",");
                    }
                    @Override public void onParameterValueChanged(
                        Parameter parameter, float oldValue, float newValue
                    ) { record("on"); }
                    @Override public void afterSetParameterValue(
                        Parameter parameter, float value
                    ) { record("after"); }
                }
                """.formatted(EVENT_PROPERTY, EVENT_PROPERTY), StandardCharsets.UTF_8);
            final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            Files.createDirectories(classes);
            final int result = compiler.run(
                null, null, null,
                "-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString(),
                source.toString()
            );
            if (result != 0) throw new IllegalStateException("fixture compilation failed");
            Files.createDirectories(pluginDir);
            try (JarOutputStream output = new JarOutputStream(
                Files.newOutputStream(pluginDir.resolve("parameter-hook.jar"))
            )) {
                try (var paths = Files.walk(classes)) {
                    for (Path path : paths.filter(Files::isRegularFile)
                        .sorted(Comparator.naturalOrder()).toList()) {
                        add(output, classes.relativize(path).toString().replace('\\', '/'),
                            Files.readAllBytes(path));
                    }
                }
                add(output, "META-INF/turboism/plugin.json", descriptor().getBytes(StandardCharsets.UTF_8));
                add(output, "META-INF/turboism/i18n/messages.properties", new byte[0]);
            }
            System.clearProperty(EVENT_PROPERTY);
        }

        static List<String> events() {
            final String value = System.getProperty(EVENT_PROPERTY, "");
            if (value.isBlank()) return List.of();
            return java.util.Arrays.stream(value.split(","))
                .filter(item -> !item.isBlank())
                .toList();
        }

        private static String descriptor() {
            return """
                {"format":"turboism.plugin.meta","schemaVersion":2,
                "id":"dev.example.parameter-hooks","name":"Hooks","version":"0.1.0",
                "description":"test","entrypoints":["dev.example.hooks.HookPlugin"],
                "turboismApi":"[0.1.0,0.2.0)","authors":[{"name":"Tests"}],
                "license":"Test","website":"https://turboism.dev","resources":[],
                "i18n":{"baseName":"META-INF/turboism/i18n/messages","locales":[]},
                "dependencies":[],"permissions":[
                  {"id":"turboism.cubism.model.observe","scope":"application","reason":"test"}
                ],"capabilities":[],
                "environment":{"requiresCubism":false,"ui":"none"}}
                """;
        }

        private static void add(JarOutputStream output, String name, byte[] content)
            throws Exception {
            output.putNextEntry(new JarEntry(name));
            output.write(content);
            output.closeEntry();
        }
    }
}
