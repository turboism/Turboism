package dev.turboism.tests.preview;

import dev.turboism.bootstrap.HostRuntimeIngress;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.preview.LocalPluginRuntime;
import dev.turboism.preview.PreviewLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPluginRuntimeMultiEntrypointIntegrationTest {

    @TempDir
    Path temporary;

    @Test
    void runsEntrypointsForwardAndStopsThemInReverse() throws Exception {
        final Path marker = temporary.resolve("success/lifecycle.txt");
        final Scenario scenario = scenario("success", marker, false);
        try {
            final LocalPluginRuntime.LoadReport report = scenario.runtime().loadAll();
            assertTrue(report.failures().isEmpty(), report.failures().toString());
            assertEquals(2, report.loaded().size());
            assertEquals("ENABLED", report.loaded().stream().filter(plugin -> !plugin.id().equals("turboism.core")).findFirst().orElseThrow().state().name());
        } finally {
            scenario.close();
        }
        assertEquals(List.of(
            "init:A",
            "init:B",
            "enable:A",
            "enable:B",
            "disable:B",
            "disable:A",
            "shutdown:B",
            "shutdown:A"
        ), Files.readAllLines(marker));
    }

    @Test
    void rollsBackWholeJarWhenSecondEntrypointEnableFails() throws Exception {
        final Path marker = temporary.resolve("failure/lifecycle.txt");
        final Scenario scenario = scenario("failure", marker, true);
        try {
            final LocalPluginRuntime.LoadReport report = scenario.runtime().loadAll();
            assertEquals(List.of("turboism.core"), report.loaded().stream().map(LocalPluginRuntime.LoadedPluginSummary::id).toList());
            assertEquals(1, report.failures().size());
            assertEquals("ENABLE_FAILED", report.failures().get(0).code());
        } finally {
            scenario.close();
        }
        assertEquals(List.of(
            "init:A",
            "init:B",
            "enable:A",
            "enable:B",
            "disable:A",
            "shutdown:B",
            "shutdown:A"
        ), Files.readAllLines(marker));
    }

    private Scenario scenario(
        final String name,
        final Path marker,
        final boolean failSecondEnable
    ) throws Exception {
        final Path home = temporary.resolve(name + "-home");
        MultiEntrypointPluginJarFixture.write(
            home.resolve("plugins"),
            temporary.resolve(name + "-build")
        );
        System.setProperty(
            MultiEntrypointPluginJarFixture.MARKER_PROPERTY,
            marker.toString()
        );
        System.setProperty(
            MultiEntrypointPluginJarFixture.FAIL_PROPERTY,
            Boolean.toString(failSecondEnable)
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
        return new Scenario(runtime, hostIngress, scheduler, log);
    }

    private static RuntimeScheduler scheduler() {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(
                1,
                16,
                ignored -> { },
                Clock.systemUTC()
            ),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
    }

    private record Scenario(
        LocalPluginRuntime runtime,
        HostRuntimeIngress hostIngress,
        RuntimeScheduler scheduler,
        PreviewLog log
    ) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            try {
                runtime.close();
            } finally {
                hostIngress.close();
                scheduler.shutdown();
                log.close();
                System.clearProperty(MultiEntrypointPluginJarFixture.MARKER_PROPERTY);
                System.clearProperty(MultiEntrypointPluginJarFixture.FAIL_PROPERTY);
            }
        }
    }
}
