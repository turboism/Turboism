package dev.turboism.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.host.HostSession;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end integration tests for plugin hot lifecycle:
 * - Load, unload, and reload generation cycle with classloader close;
 * - Stale-reference fencing after hot reload;
 * - Rollback to the previous generation on reload failure;
 * - Gating a hook-contributing plugin to restart-required;
 * - Handling Windows-style file locks via fallback to staging.
 */
class PluginHotReloadIntegrationTest {

    private static final String PLUGIN_ID = "dev.example.hotreloadable";
    private static final String PROP_ACTIVE_VERSION = "dev.turboism.test.active.version";

    @TempDir
    Path temporary;

    @Test
    void hotReloadCycleUnloadsOldGenerationAndClosesClassLoader() throws Exception {
        final Path home = temporary.resolve("hot-home");
        final Path plugins = home.resolve("plugins");
        Files.createDirectories(plugins);

        final Path v1Jar = plugins.resolve(PLUGIN_ID + ".jar");
        writeJar(v1Jar, PLUGIN_ID, "0.1.0", true, false, """
                package dev.example.hotreloadable;
                import dev.turboism.sdk.plugin.*;
                public final class TestEntrypoint implements TurboismPlugin {
                    public static PluginContext LAST_CONTEXT;
                    @Override public void init(PluginContext context) {
                        LAST_CONTEXT = context;
                        System.setProperty("dev.turboism.test.active.version", "0.1.0");
                    }
                }
                """);

        try (LocalPluginRuntime runtime = createRuntime(home)) {
            runtime.loadAll();
            assertEquals("0.1.0", System.getProperty(PROP_ACTIVE_VERSION));
            final PluginHotReloadCoordinator coordinator = runtime.hotReloadCoordinator();
            assertTrue(coordinator.isLoaded(PLUGIN_ID));

            final LocalPluginRuntime.LoadedPlugin v1Loaded =
                    coordinator.findLoaded(PLUGIN_ID).orElseThrow();
            final URLClassLoader v1ClassLoader = v1Loaded.classLoader();

            // Prepare replacement v2 JAR
            final Path v2Jar = temporary.resolve("v2-replacement.jar");
            writeJar(v2Jar, PLUGIN_ID, "0.2.0", true, false, """
                package dev.example.hotreloadable;
                import dev.turboism.sdk.plugin.*;
                public final class TestEntrypoint implements TurboismPlugin {
                    @Override public void init(PluginContext context) {
                        System.setProperty("dev.turboism.test.active.version", "0.2.0");
                    }
                }
                """);

            // Execute hot reload
            final var result = coordinator.hotReload(PLUGIN_ID, v2Jar, v1Jar);
            assertTrue(result.succeeded(), "hot reload must succeed: " + result.message());
            assertEquals("0.2.0", System.getProperty(PROP_ACTIVE_VERSION));

            // Prove old generation was unloaded and classloader closed
            assertEquals(
                    dev.turboism.core.lifecycle.PluginLifecycleState.UNLOADED,
                    v1Loaded.runtime().state());
            assertFalse(runtime.loadedPlugins().stream()
                    .anyMatch(p -> p.id().equals(PLUGIN_ID) && "0.1.0".equals(p.version())));
            // Jar file handle released: file can be deleted or replaced cleanly
            Files.delete(v1Jar);
            assertFalse(Files.exists(v1Jar));

            final LocalPluginRuntime.LoadedPlugin v2Loaded =
                    coordinator.findLoaded(PLUGIN_ID).orElseThrow();
            assertNotNull(v2Loaded);
            assertEquals("0.2.0", v2Loaded.runtime().descriptor().version());
        }
    }

    @Test
    void staleReferenceFencedAfterHotReload() throws Exception {
        final Path home = temporary.resolve("fence-home");
        final Path plugins = home.resolve("plugins");
        Files.createDirectories(plugins);

        final Path v1Jar = plugins.resolve(PLUGIN_ID + ".jar");
        writeJar(v1Jar, PLUGIN_ID, "0.1.0", true, false, """
                package dev.example.hotreloadable;
                import dev.turboism.sdk.plugin.*;
                public final class TestEntrypoint implements TurboismPlugin {
                    public static PluginContext LAST_CONTEXT;
                    @Override public void init(PluginContext context) {
                        LAST_CONTEXT = context;
                    }
                }
                """);

        try (LocalPluginRuntime runtime = createRuntime(home)) {
            runtime.loadAll();
            final LocalPluginRuntime.LoadedPlugin v1Loaded =
                    runtime.hotReloadCoordinator().findLoaded(PLUGIN_ID).orElseThrow();
            final Class<?> v1Class = v1Loaded.classLoader().loadClass("dev.example.hotreloadable.TestEntrypoint");
            final PluginContext staleContext =
                    (PluginContext) v1Class.getField("LAST_CONTEXT").get(null);
            assertNotNull(staleContext);

            // Access works before reload
            assertNotNull(staleContext.paths().dataDir());

            final Path v2Jar = temporary.resolve("v2.jar");
            writeJar(v2Jar, PLUGIN_ID, "0.2.0", true, false, """
                package dev.example.hotreloadable;
                import dev.turboism.sdk.plugin.*;
                public final class TestEntrypoint implements TurboismPlugin {}
                """);

            final var result = runtime.hotReloadCoordinator().hotReload(PLUGIN_ID, v2Jar, v1Jar);
            assertTrue(result.succeeded());

            // Stale context calls are strictly fenced by PluginGenerationGuard
            final IllegalStateException failure = assertThrows(
                    IllegalStateException.class, () -> staleContext.paths().dataDir());
            assertTrue(
                    failure.getMessage().contains("closed"), "Expected closed message, got: " + failure.getMessage());
        }
    }

    @Test
    void failureRollsBackToPreviousGeneration() throws Exception {
        final Path home = temporary.resolve("rollback-home");
        final Path plugins = home.resolve("plugins");
        Files.createDirectories(plugins);

        final Path v1Jar = plugins.resolve(PLUGIN_ID + ".jar");
        writeJar(v1Jar, PLUGIN_ID, "0.1.0", true, false, """
                package dev.example.hotreloadable;
                import dev.turboism.sdk.plugin.*;
                public final class TestEntrypoint implements TurboismPlugin {
                    @Override public void init(PluginContext context) {
                        System.setProperty("dev.turboism.test.active.version", "0.1.0");
                    }
                }
                """);

        try (LocalPluginRuntime runtime = createRuntime(home)) {
            runtime.loadAll();
            assertEquals("0.1.0", System.getProperty(PROP_ACTIVE_VERSION));

            // Broken v2 JAR that fails during init
            final Path brokenJar = temporary.resolve("v2-broken.jar");
            writeJar(brokenJar, PLUGIN_ID, "0.2.0", true, false, """
                package dev.example.hotreloadable;
                import dev.turboism.sdk.plugin.*;
                public final class TestEntrypoint implements TurboismPlugin {
                    @Override public void init(PluginContext context) {
                        throw new RuntimeException("Deliberate failure in v2 init");
                    }
                }
                """);

            final var reloadResult = runtime.hotReloadCoordinator().hotReload(PLUGIN_ID, brokenJar, v1Jar);
            assertTrue(reloadResult.rolledBack(), "Should report rolledBack on failure: " + reloadResult.message());

            // Prove previous generation is active
            final LocalPluginRuntime.LoadedPlugin rolledBackLoaded =
                    runtime.hotReloadCoordinator().findLoaded(PLUGIN_ID).orElseThrow();
            assertEquals("0.1.0", rolledBackLoaded.runtime().descriptor().version());
            assertEquals("0.1.0", System.getProperty(PROP_ACTIVE_VERSION));
        }
    }

    /**
     * A rollback whose reload of the previous generation fails must report a failure. The old
     * code discarded the {@code loader.load} result and answered {@code rolledBack} anyway, which
     * told the caller a plugin was running again when nothing was.
     */
    @Test
    void rollbackLoadFailureReportsFailureInsteadOfRolledBack() throws Exception {
        final Path home = temporary.resolve("rollback-load-failure-home");
        final Path plugins = home.resolve("plugins");
        Files.createDirectories(plugins);

        final Path v1Jar = plugins.resolve(PLUGIN_ID + ".jar");
        writeJar(v1Jar, PLUGIN_ID, "0.1.0", true, false, """
                package dev.example.hotreloadable;
                import dev.turboism.sdk.plugin.*;
                public final class TestEntrypoint implements TurboismPlugin {}
                """);

        try (LocalPluginRuntime runtime = createRuntime(home)) {
            runtime.loadAll();
            assertTrue(runtime.hotReloadCoordinator().isLoaded(PLUGIN_ID));

            final Path brokenReplacement = temporary.resolve("v2-broken-again.jar");
            writeJar(brokenReplacement, PLUGIN_ID, "0.2.0", true, false, """
                package dev.example.hotreloadable;
                import dev.turboism.sdk.plugin.*;
                public final class TestEntrypoint implements TurboismPlugin {
                    @Override public void init(PluginContext context) {
                        throw new RuntimeException("Deliberate replacement failure");
                    }
                }
                """);
            final Path brokenRollback = temporary.resolve("rollback-broken.jar");
            writeJar(brokenRollback, PLUGIN_ID, "0.1.0", true, false, """
                package dev.example.hotreloadable;
                import dev.turboism.sdk.plugin.*;
                public final class TestEntrypoint implements TurboismPlugin {
                    @Override public void init(PluginContext context) {
                        throw new RuntimeException("Deliberate rollback failure");
                    }
                }
                """);

            final var result = runtime.hotReloadCoordinator().hotReload(PLUGIN_ID, brokenReplacement, brokenRollback);

            assertFalse(result.succeeded(), "a failed rollback is not a success");
            assertFalse(result.rolledBack(), "nothing was restored, so the result must not claim a rollback");
            assertEquals("ROLLBACK_FAILED", result.code());
            assertFalse(
                    runtime.hotReloadCoordinator().isLoaded(PLUGIN_ID),
                    "no generation is live after a failed rollback");
        }
    }

    /** An unreadable rollback candidate must fail the reload instead of reporting a rollback. */
    @Test
    void unreadableRollbackCandidateReportsFailureInsteadOfRolledBack() throws Exception {
        final Path home = temporary.resolve("rollback-candidate-home");
        final Path plugins = home.resolve("plugins");
        Files.createDirectories(plugins);

        final Path v1Jar = plugins.resolve(PLUGIN_ID + ".jar");
        writeJar(v1Jar, PLUGIN_ID, "0.1.0", true, false, """
                package dev.example.hotreloadable;
                import dev.turboism.sdk.plugin.*;
                public final class TestEntrypoint implements TurboismPlugin {}
                """);

        try (LocalPluginRuntime runtime = createRuntime(home)) {
            runtime.loadAll();

            final Path brokenReplacement = temporary.resolve("v2-broken-candidate.jar");
            writeJar(brokenReplacement, PLUGIN_ID, "0.2.0", true, false, """
                package dev.example.hotreloadable;
                import dev.turboism.sdk.plugin.*;
                public final class TestEntrypoint implements TurboismPlugin {
                    @Override public void init(PluginContext context) {
                        throw new RuntimeException("Deliberate replacement failure");
                    }
                }
                """);
            final Path unreadableRollback = temporary.resolve("rollback-without-descriptor.jar");
            writeJarWithoutPluginDescriptor(unreadableRollback);

            final var result =
                    runtime.hotReloadCoordinator().hotReload(PLUGIN_ID, brokenReplacement, unreadableRollback);

            assertFalse(result.succeeded());
            assertFalse(result.rolledBack(), "an unreadable candidate cannot roll anything back");
            assertEquals("ROLLBACK_FAILED", result.code());
        }
    }

    /**
     * A generation whose teardown failed stays under live management: the reload reports
     * {@code UNLOAD_FAILED}, and the still-holding generation is re-registered so a later runtime
     * close can reach it instead of leaking it.
     */
    @Test
    void failedUnloadKeepsTheLiveGenerationRegistered() throws Exception {
        final Path home = temporary.resolve("unload-failure-home");
        final Path plugins = home.resolve("plugins");
        Files.createDirectories(plugins);

        final Path v1Jar = plugins.resolve(PLUGIN_ID + ".jar");
        writeJar(v1Jar, PLUGIN_ID, "0.1.0", true, false, """
                package dev.example.hotreloadable;
                import dev.turboism.sdk.plugin.*;
                public final class TestEntrypoint implements TurboismPlugin {}
                """);
        final Path v2Jar = temporary.resolve("v2-unload-failure.jar");
        writeJar(v2Jar, PLUGIN_ID, "0.2.0", true, false, """
                package dev.example.hotreloadable;
                import dev.turboism.sdk.plugin.*;
                public final class TestEntrypoint implements TurboismPlugin {}
                """);

        final java.util.concurrent.atomic.AtomicBoolean failClose =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        try (LocalPluginRuntime runtime = createRuntime(home, (pluginId, phase) -> {
            if (failClose.get() && PLUGIN_ID.equals(pluginId) && "close".equals(phase)) {
                throw new IllegalStateException("Deliberate close failure");
            }
        })) {
            runtime.loadAll();
            assertTrue(runtime.hotReloadCoordinator().isLoaded(PLUGIN_ID));

            final var result = runtime.hotReloadCoordinator().hotReload(PLUGIN_ID, v2Jar, v1Jar);

            assertFalse(result.succeeded());
            assertEquals("UNLOAD_FAILED", result.code());
            assertTrue(
                    runtime.hotReloadCoordinator().isLoaded(PLUGIN_ID),
                    "a generation whose teardown failed must stay registered so it can still be closed");
            failClose.set(false);
        }
    }

    @Test
    void hookContributingPluginForcedToRestartRequired() throws Exception {
        final Path home = temporary.resolve("hook-home");
        final Path plugins = home.resolve("plugins");
        Files.createDirectories(plugins);

        final Path hookJar = plugins.resolve("dev.example.hookplugin.jar");
        writeJar(
                hookJar,
                "dev.example.hookplugin",
                "0.1.0",
                true,
                true, // contains META-INF/turboism/hooks
                """
                package dev.example.hookplugin;
                import dev.turboism.sdk.plugin.*;
                public final class HookEntrypoint implements TurboismPlugin {}
                """);

        try (LocalPluginRuntime runtime = createRuntime(home)) {
            final PluginHotReloadCoordinator coordinator = runtime.hotReloadCoordinator();

            // isHotReloadSafe must reject because of META-INF/turboism/hooks
            assertFalse(coordinator.isHotReloadSafe(hookJar), "Hook contributing JAR must NOT be hot reload safe");

            final var loadResult = coordinator.hotLoad(hookJar);
            assertFalse(loadResult.succeeded(), "Hot load must fail closed for hook plugin");
            assertEquals("HOT_RELOAD_UNSAFE", loadResult.code());
        }
    }

    private LocalPluginRuntime createRuntime(final Path home) throws IOException {
        return createRuntime(home, (pluginId, phase) -> {});
    }

    private LocalPluginRuntime createRuntime(final Path home, final LocalPluginRuntime.PluginCloseHook closeHook)
            throws IOException {
        final RuntimeScheduler scheduler = new RuntimeScheduler(
                new DefaultWorkBudgetPolicy(),
                new PluginWorkExecutorRegistry(1, 16, ignored -> {}, Clock.systemUTC()),
                SidecarDispatcher.noop(),
                ignored -> {});
        final HostSession host = new HostSession(Optional::empty);
        final PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"));
        return new LocalPluginRuntime(home, scheduler, host.adapterAccess(), log, closeHook);
    }

    /** A syntactically valid archive that carries no plugin descriptor, so discovery rejects it. */
    private static void writeJarWithoutPluginDescriptor(final Path destination) throws Exception {
        Files.createDirectories(destination.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(destination))) {
            addEntry(out, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void writeJar(
            final Path destination,
            final String pluginId,
            final String version,
            final boolean hotReload,
            final boolean includeHooks,
            final String source)
            throws Exception {
        final Path sourceDir = temporary.resolve("src-" + destination.getFileName());
        final Path classesDir = temporary.resolve("classes-" + destination.getFileName());
        final String className = source.contains("class TestEntrypoint") ? "TestEntrypoint" : "HookEntrypoint";
        final Path javaFile = sourceDir.resolve(
                "dev/example/" + pluginId.substring(pluginId.lastIndexOf('.') + 1) + "/" + className + ".java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, source, StandardCharsets.UTF_8);
        Files.createDirectories(classesDir);

        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final int res = compiler.run(
                null,
                null,
                err,
                "-classpath",
                System.getProperty("java.class.path"),
                "-d",
                classesDir.toString(),
                javaFile.toString());
        assertEquals(0, res, "compilation failed: " + err.toString(StandardCharsets.UTF_8));

        Files.createDirectories(destination.getParent());
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(destination))) {
            try (var stream = Files.walk(classesDir)) {
                for (Path p : stream.filter(Files::isRegularFile).sorted().toList()) {
                    addEntry(out, classesDir.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
                }
            }
            final String entrypoint = "dev.example." + pluginId.substring(pluginId.lastIndexOf('.') + 1)
                    + (source.contains("TestEntrypoint") ? ".TestEntrypoint" : ".HookEntrypoint");
            final String descriptor = """
                {
                  "format": "turboism.plugin.meta",
                  "schemaVersion": 3,
                  "id": "%s",
                  "name": "Test Plugin",
                  "version": "%s",
                  "description": "Test",
                  "entrypoints": ["%s"],
                  "turboismApi": "[0.67.0,1.0.0)",
                  "authors": [{"name": "Test"}],
                  "license": "Test",
                  "website": "https://turboism.dev",
                  "resources": [],
                  "i18n": {"baseName": "META-INF/turboism/i18n/messages", "locales": []},
                  "dependencies": [],
                  "permissions": [],
                  "capabilities": [],
                  "environment": {"requiresCubism": false, "ui": "none"},
                  "lifecycle": {"hotReload": %s},
                  "category": "workflow",
                  "tags": ["test"]
                }
                """.formatted(pluginId, version, entrypoint, hotReload);

            addEntry(out, "META-INF/turboism/plugin.json", descriptor.getBytes(StandardCharsets.UTF_8));
            addEntry(out, "META-INF/turboism/i18n/messages.properties", new byte[0]);
            if (includeHooks) {
                addEntry(
                        out,
                        "META-INF/turboism/hooks",
                        "dev.turboism.bootstrap.TestHookContributor\n".getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static void addEntry(final JarOutputStream out, final String path, final byte[] data) throws Exception {
        out.putNextEntry(new JarEntry(path));
        out.write(data);
        out.closeEntry();
    }
}
