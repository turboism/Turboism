package dev.turboism.preview;

import dev.turboism.adapter.cubism.lifecycle.DrawableLifecycleCoordinator;
import dev.turboism.adapter.host.HostSession;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.IntSequence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPluginRuntimeEditorObjectHookScopeIntegrationTest {

    @TempDir
    Path temporary;

    @Test
    void pluginScopeCloseAndRuntimeShutdownWaitForAcceptedEditorObjectCallback() throws Exception {
        final Path pluginDir = temporary.resolve("plugins");
        Files.createDirectories(pluginDir);
        EditorObjectScopePluginFixture.write(pluginDir, temporary);
        final RuntimeScheduler scheduler = scheduler();
        final HostSession host = new HostSession(Optional::empty);
        final LocalPluginRuntime runtime = new LocalPluginRuntime(
            temporary, scheduler, host.adapterAccess(),
            new PreviewLog(temporary.resolve("logs/turboism.log"))
        );
        final DrawableLifecycleCoordinator lifecycle = host.editorObjectLifecycle().drawable();
        final MutableDrawable drawable = new MutableDrawable();
        final CountDownLatch runtimeClosed = new CountDownLatch(1);
        final Thread closeRuntime = new Thread(() -> {
            try {
                runtime.close();
            } finally {
                runtimeClosed.countDown();
            }
        });
        EditorObjectScopePluginFixture.clear();
        try {
            runtime.loadAll();
            lifecycle.setOpacity(drawable, 0.5F, drawable::writeOpacity);
            assertTrue(EditorObjectScopePluginFixture.await("started", 5_000L));

            System.setProperty(EditorObjectScopePluginFixture.CLOSE_REQUESTED, "true");
            assertTrue(EditorObjectScopePluginFixture.await("closing", 5_000L));
            assertFalse(EditorObjectScopePluginFixture.is("closed"));

            closeRuntime.start();
            assertFalse(
                runtimeClosed.await(200, TimeUnit.MILLISECONDS),
                "runtime shutdown must not release the plugin while its scope-owned callback is running"
            );

            System.setProperty(EditorObjectScopePluginFixture.RELEASE, "true");
            assertTrue(EditorObjectScopePluginFixture.await("closed", 5_000L));
            assertTrue(runtimeClosed.await(5, TimeUnit.SECONDS));
            closeRuntime.join(5_000L);

            final int callbacksAfterClose = EditorObjectScopePluginFixture.callbackCount();
            lifecycle.setOpacity(drawable, 0.75F, drawable::writeOpacity);
            lifecycle.awaitIdle();
            assertEquals(callbacksAfterClose, EditorObjectScopePluginFixture.callbackCount());
            assertEquals(0.75F, drawable.getOpacity());
        } finally {
            System.setProperty(EditorObjectScopePluginFixture.RELEASE, "true");
            if (closeRuntime.isAlive()) {
                closeRuntime.join(5_000L);
            } else if (runtimeClosed.getCount() != 0L) {
                runtime.close();
            }
            EditorObjectScopePluginFixture.clear();
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

    private static final class MutableDrawable implements Drawable {
        private volatile float opacity = 1.0F;
        @Override public ArtMeshId id() { return new ArtMeshId("ArtMeshA"); }
        @Override public float getOpacity() { return opacity; }
        @Override public void setOpacity(float value) { opacity = value; }
        void writeOpacity(float value) { opacity = value; }
        @Override public byte constantFlag() { return 0; }
        @Override public byte dynamicFlag() { return 0; }
        @Override public BlendMode blendMode() { return BlendMode.NORMAL; }
        @Override public int textureIndex() { return 0; }
        @Override public int drawOrder() { return 0; }
        @Override public int renderOrder() { return 0; }
        @Override public IntSequence masks() { return emptyInts(); }
        @Override public FloatSequence vertexPositions() { return emptyFloats(); }
        @Override public FloatSequence vertexUvs() { return emptyFloats(); }
        @Override public IntSequence indices() { return emptyInts(); }
        @Override public Color multiplyColor() { return new Color(1, 1, 1, 1); }
        @Override public Color screenColor() { return new Color(0, 0, 0, 1); }
        @Override public int parentPartIndex() { return -1; }
        @Override public int parentDeformerIndex() { return -1; }
        @Override public IntSequence parameters() { return emptyInts(); }
    }

    private static IntSequence emptyInts() {
        return new IntSequence() {
            @Override public int size() { return 0; }
            @Override public int get(int index) { throw new IndexOutOfBoundsException(index); }
        };
    }

    private static FloatSequence emptyFloats() {
        return new FloatSequence() {
            @Override public int size() { return 0; }
            @Override public float get(int index) { throw new IndexOutOfBoundsException(index); }
        };
    }

    static final class EditorObjectScopePluginFixture {
        static final String PREFIX = "dev.turboism.preview.editor-object-scope.";
        static final String PHASE = PREFIX + "phase";
        static final String CALLBACKS = PREFIX + "callbacks";
        static final String CLOSE_REQUESTED = PREFIX + "close-requested";
        static final String RELEASE = PREFIX + "release";

        static void write(final Path pluginDir, final Path temporary) throws Exception {
            final Path sourceRoot = temporary.resolve("editor-object-scope-source");
            final Path classes = temporary.resolve("editor-object-scope-classes");
            final Path source = sourceRoot.resolve("dev/example/hooks/ScopeHookPlugin.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, """
                package dev.example.hooks;

                import dev.turboism.sdk.cubism.CubismPlugin;
                import dev.turboism.sdk.cubism.model.Drawable;
                import dev.turboism.sdk.plugin.PluginContext;

                public final class ScopeHookPlugin implements CubismPlugin {
                    private PluginContext context;

                    @Override public void init(PluginContext value) {
                        context = value;
                    }

                    @Override public void enable() {
                        Thread closer = new Thread(() -> {
                            try {
                                while (!Boolean.getBoolean("%s")) Thread.sleep(5L);
                                System.setProperty("%s", "closing");
                                context.disposableScope().close();
                                System.setProperty("%s", "closed");
                            } catch (Throwable failure) {
                                System.setProperty("%s", "failed:" + failure.getClass().getName());
                            }
                        }, "editor-object-scope-closer");
                        closer.setDaemon(true);
                        closer.start();
                    }

                    @Override public void afterSetDrawableOpacity(Drawable drawable, float value) {
                        int count = Integer.parseInt(System.getProperty("%s", "0")) + 1;
                        System.setProperty("%s", Integer.toString(count));
                        System.setProperty("%s", "started");
                        try {
                            while (!Boolean.getBoolean("%s")) Thread.sleep(5L);
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                """.formatted(
                    CLOSE_REQUESTED, PHASE, PHASE, PHASE,
                    CALLBACKS, CALLBACKS, PHASE, RELEASE
                ), StandardCharsets.UTF_8);
            final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            Files.createDirectories(classes);
            final int result = compiler.run(
                null, null, null,
                "-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString(), source.toString()
            );
            if (result != 0) throw new IllegalStateException("fixture compilation failed");
            try (JarOutputStream output = new JarOutputStream(
                Files.newOutputStream(pluginDir.resolve("editor-object-scope.jar"))
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
        }

        static boolean await(final String phase, final long timeoutMillis) throws InterruptedException {
            final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (System.nanoTime() < deadline) {
                if (is(phase)) return true;
                Thread.sleep(10L);
            }
            return is(phase);
        }

        static boolean is(final String phase) {
            return phase.equals(System.getProperty(PHASE));
        }

        static int callbackCount() {
            return Integer.parseInt(System.getProperty(CALLBACKS, "0"));
        }

        static void clear() {
            System.clearProperty(PHASE);
            System.clearProperty(CALLBACKS);
            System.clearProperty(CLOSE_REQUESTED);
            System.clearProperty(RELEASE);
        }

        private static String descriptor() {
            return """
                {"format":"turboism.plugin.meta","schemaVersion":2,
                "id":"dev.example.editor-object-scope","name":"Editor Object Scope","version":"0.1.0",
                "description":"test","entrypoints":["dev.example.hooks.ScopeHookPlugin"],
                "turboismApi":"[0.1.0,0.2.0)","authors":[{"name":"Tests"}],
                "license":"Test","website":"https://turboism.dev","resources":[],
                "i18n":{"baseName":"META-INF/turboism/i18n/messages","locales":[]},
                "dependencies":[],"permissions":[
                  {"id":"turboism.cubism.model.observe","scope":"application","reason":"test"}
                ],"capabilities":[],
                "environment":{"requiresCubism":false,"ui":"none"}}
                """;
        }

        private static void add(JarOutputStream output, String name, byte[] content) throws Exception {
            output.putNextEntry(new JarEntry(name));
            output.write(content);
            output.closeEntry();
        }
    }
}
