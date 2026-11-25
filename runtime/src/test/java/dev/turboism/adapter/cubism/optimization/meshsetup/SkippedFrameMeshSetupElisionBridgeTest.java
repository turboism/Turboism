package dev.turboism.adapter.cubism.optimization.meshsetup;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipBridge;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkippedFrameMeshSetupElisionBridgeTest {
    @TempDir
    Path directory;

    /** Real bridge reflection and callbacks against a loader-isolated host stand-in. */
    private final class Host implements AutoCloseable {
        final URLClassLoader loader;
        final Object gl, context, frame, mesh;
        final SkippedFrameMeshSetupElisionBridge bridge;
        final AtomicBoolean skipped = new AtomicBoolean(true);
        final Object oldSkipped;

        Host() throws Exception {
            final var sources = Map.of(
                    "com/jogamp/opengl/GL.java", """
                        package com.jogamp.opengl;
                        public class GL {
                            public GLContext context = new GLContext();
                            public GLContext getContext() { return context; }
                        }
                        """,
                    "com/jogamp/opengl/GL3.java", """
                        package com.jogamp.opengl;
                        public class GL3 extends GL {}
                        """,
                    "com/jogamp/opengl/GLContext.java", """
                        package com.jogamp.opengl;
                        public class GLContext {
                            public static GLContext current;
                            public boolean created = true, shared;
                            public long handle = 1;
                            public static GLContext getCurrent() { return current; }
                            public boolean isCreated() { return created; }
                            public boolean isShared() { return shared; }
                            public long getHandle() { return handle; }
                        }
                        """,
                    "com/live2d/graphics3d/a.java", """
                        package com.live2d.graphics3d;
                        public class a {
                            public static com.jogamp.opengl.GL3 gl;
                            public com.jogamp.opengl.GL3 a() { return gl; }
                        }
                        """,
                    "com/live2d/graphics3d/mesh/a/a.java", """
                        package com.live2d.graphics3d.mesh.a;
                        public abstract class a {
                            private boolean dirty;
                            private final java.nio.IntBuffer names = java.nio.IntBuffer.allocate(1);
                            protected final java.nio.IntBuffer i() { return names; }
                            protected final boolean k() { return dirty; }
                            public final void dirty(boolean value) { dirty = value; }
                            public final void name(int value) { names.put(0, value); }
                        }
                        """,
                    "com/live2d/graphics3d/mesh/a/b.java", """
                        package com.live2d.graphics3d.mesh.a;
                        public final class b extends a {}
                        """,
                    "com/live2d/graphics3d/mesh/a/c.java", """
                        package com.live2d.graphics3d.mesh.a;
                        public final class c extends a {}
                        """,
                    "com/live2d/graphics3d/mesh/GMesh2.java", """
                        package com.live2d.graphics3d.mesh;
                        public final class GMesh2 {
                            private final com.live2d.graphics3d.mesh.a.b positions = new com.live2d.graphics3d.mesh.a.b();
                            private final com.live2d.graphics3d.mesh.a.b normals = new com.live2d.graphics3d.mesh.a.b();
                            private final com.live2d.graphics3d.mesh.a.b uvs = new com.live2d.graphics3d.mesh.a.b();
                            private final com.live2d.graphics3d.mesh.a.b uvs2 = new com.live2d.graphics3d.mesh.a.b();
                            private final com.live2d.graphics3d.mesh.a.b uvs3 = new com.live2d.graphics3d.mesh.a.b();
                            private final com.live2d.graphics3d.mesh.a.b colors = new com.live2d.graphics3d.mesh.a.b();
                            private final com.live2d.graphics3d.mesh.a.c colors32 = new com.live2d.graphics3d.mesh.a.c();
                            private final com.live2d.graphics3d.mesh.a.b alpha = new com.live2d.graphics3d.mesh.a.b();
                            private final com.live2d.graphics3d.mesh.a.c indices = new com.live2d.graphics3d.mesh.a.c();
                            public com.live2d.graphics3d.mesh.a.b getPositionsBuffer() { return positions; }
                            public com.live2d.graphics3d.mesh.a.b getNormalsBuffer() { return normals; }
                            public com.live2d.graphics3d.mesh.a.b getUvsBuffer() { return uvs; }
                            public com.live2d.graphics3d.mesh.a.b getUvs2Buffer() { return uvs2; }
                            public com.live2d.graphics3d.mesh.a.b getUvs3Buffer() { return uvs3; }
                            public com.live2d.graphics3d.mesh.a.b getColorsBuffer() { return colors; }
                            public com.live2d.graphics3d.mesh.a.c getColors32Buffer() { return colors32; }
                            public com.live2d.graphics3d.mesh.a.b getAlphaBuffer() { return alpha; }
                            public com.live2d.graphics3d.mesh.a.c getIndicesBuffer() { return indices; }
                            public GMesh2() {
                                positions.dirty(true);
                                normals.dirty(true);
                                uvs.dirty(true);
                                uvs2.dirty(true);
                                uvs3.dirty(true);
                                colors.dirty(true);
                                colors32.dirty(true);
                                alpha.dirty(true);
                                indices.dirty(true);
                            }
                        }
                        """);
            final var arguments = new java.util.ArrayList<String>();
            arguments.add("-d");
            arguments.add(directory.toString());
            for (var source : sources.entrySet()) {
                final Path path = directory.resolve(source.getKey());
                Files.createDirectories(path.getParent());
                Files.writeString(path, source.getValue());
                arguments.add(path.toString());
            }
            assertEquals(
                    0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
            loader = new URLClassLoader(new java.net.URL[] {directory.toUri().toURL()}, null);
            gl = loader.loadClass("com.jogamp.opengl.GL3").getConstructor().newInstance();
            context = gl.getClass().getField("context").get(gl);
            context.getClass().getField("current").set(null, context);
            final Class<?> frameType = loader.loadClass("com.live2d.graphics3d.a");
            frameType.getField("gl").set(null, gl);
            frame = frameType.getConstructor().newInstance();
            mesh = loader.loadClass("com.live2d.graphics3d.mesh.GMesh2")
                    .getConstructor()
                    .newInstance();
            oldSkipped = System.getProperties().get(ModelUpdateSkipBridge.SKIPPED_FRAME_PROPERTY);
            System.getProperties().put(ModelUpdateSkipBridge.SKIPPED_FRAME_PROPERTY, skipped);
            bridge = new SkippedFrameMeshSetupElisionBridge(loader);
            bridge.install();
        }

        @SuppressWarnings("unchecked")
        boolean consult() {
            return consult(mesh);
        }

        @SuppressWarnings("unchecked")
        boolean consult(final Object instance) {
            return ((BiPredicate<Object, Object>)
                            System.getProperties().get(SkippedFrameMeshSetupElisionBridge.PREDICATE_PROPERTY))
                    .test(instance, frame);
        }

        void complete() {
            complete(mesh);
        }

        @SuppressWarnings("unchecked")
        void complete(final Object instance) {
            ((Consumer<Object>) System.getProperties().get(SkippedFrameMeshSetupElisionBridge.COMPLETE_PROPERTY))
                    .accept(instance);
        }

        void dirty(final boolean value) throws Exception {
            wrappers("dirty", boolean.class, value);
        }

        void name(final int value) throws Exception {
            wrappers("name", int.class, value);
        }

        void usable() throws Exception {
            name(1);
            dirty(false);
        }

        private void wrappers(final String method, final Class<?> parameter, final Object value) throws Exception {
            final Class<?> base = loader.loadClass("com.live2d.graphics3d.mesh.a.a");
            final var accessor = base.getMethod(method, parameter);
            for (final String getter : SkippedFrameMeshSetupElisionTarget.BUFFER_GETTERS) {
                final Object wrapper = mesh.getClass().getMethod(getter).invoke(mesh);
                accessor.invoke(wrapper, value);
            }
        }

        @Override
        public void close() throws Exception {
            bridge.close();
            loader.close();
            if (oldSkipped == null) System.getProperties().remove(ModelUpdateSkipBridge.SKIPPED_FRAME_PROPERTY);
            else System.getProperties().put(ModelUpdateSkipBridge.SKIPPED_FRAME_PROPERTY, oldSkipped);
        }
    }

    @Test
    void elidesOnlyAfterACompletedSetupAndAllBuffersAreClean() throws Exception {
        try (var host = new Host()) {
            assertFalse(host.consult(), "new mesh has no setup baseline");
            host.complete();
            assertTrue(host.consult(), "wrappers that never owned a GL name (unused or not yet rendered) do not block");
            host.name(1);
            host.dirty(true);
            assertFalse(host.consult(), "an uploaded wrapper with pending content blocks elision");
            host.usable();
            assertTrue(host.consult());

            host.skipped.set(false);
            assertFalse(host.consult(), "non-skipped frames always run setup");
            host.skipped.set(true);
            host.dirty(true);
            assertFalse(host.consult(), "a pending wrapper buffer forces a native setup");
            host.usable();
            assertTrue(host.consult());
        }
    }

    @Test
    void contextGenerationChangeInvalidatesEveryTrackedMesh() throws Exception {
        try (var host = new Host()) {
            assertFalse(host.consult());
            host.complete();
            host.usable();
            assertTrue(host.consult());
            host.context.getClass().getField("handle").setLong(host.context, 2L);
            assertFalse(host.consult(), "the first setup in a new context must run natively");
            host.complete();
            assertTrue(host.consult());
            host.context.getClass().getField("current").set(null, null);
            assertFalse(host.consult(), "an uncurrent context cannot be elided");
            host.context.getClass().getField("current").set(null, host.context);
            assertTrue(host.consult());
            host.context.getClass().getField("created").setBoolean(host.context, false);
            assertFalse(host.consult(), "an uncreated context cannot be elided");
        }
    }

    @Test
    void anotherMeshNeedsItsOwnCompletedSetup() throws Exception {
        try (var host = new Host()) {
            assertFalse(host.consult());
            host.complete();
            host.usable();
            assertTrue(host.consult());
            final Object other = host.loader
                    .loadClass("com.live2d.graphics3d.mesh.GMesh2")
                    .getConstructor()
                    .newInstance();
            assertFalse(host.consult(other), "instance identity is part of the baseline");
            host.complete(other);
            assertTrue(host.consult(other), "its own context-generation baseline now exists");
        }
    }

    @Test
    void steadyStateHitAndCompleteDoNotAllocateOnHeap() throws Exception {
        final com.sun.management.ThreadMXBean threadMx =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                threadMx != null && threadMx.isThreadAllocatedMemorySupported(),
                "ThreadMXBean allocation tracking unavailable");
        threadMx.setThreadAllocatedMemoryEnabled(true);
        try (var host = new Host()) {
            host.usable();
            host.complete();
            assertTrue(host.consult());
            for (int i = 0; i < 5_000; i++) {
                assertTrue(host.consult());
                host.complete();
            }
            long minDelta = Long.MAX_VALUE;
            final long tid = Thread.currentThread().getId();
            for (int pass = 0; pass < 3; pass++) {
                final long before = threadMx.getThreadAllocatedBytes(tid);
                for (int i = 0; i < 2_000; i++) {
                    if (!host.consult()) {
                        throw new AssertionError("expected steady-state elision");
                    }
                    host.complete();
                }
                final long after = threadMx.getThreadAllocatedBytes(tid);
                minDelta = Math.min(minDelta, after - before);
            }
            assertEquals(0L, minDelta, "steady-state mesh setup hit and completion must allocate zero bytes");
        }
    }

    @Test
    void transientMeshesAreGcCollectableAndSafeUnderConcurrentSnapshots() throws Exception {
        try (var host = new Host()) {
            host.usable();
            final Class<?> meshType = host.loader.loadClass("com.live2d.graphics3d.mesh.GMesh2");
            @SuppressWarnings("unchecked")
            final var statsSupplier = (java.util.function.Supplier<java.util.Map<String, Long>>)
                    System.getProperties().get(SkippedFrameMeshSetupElisionBridge.STATS_PROPERTY);
            final int batch = 64;
            final java.util.List<java.lang.ref.WeakReference<Object>> weakRefs = new java.util.ArrayList<>(batch);
            for (int i = 0; i < batch; i++) {
                Object transientMesh = meshType.getConstructor().newInstance();
                weakRefs.add(new java.lang.ref.WeakReference<>(transientMesh));
                host.complete(transientMesh);
                assertTrue(host.consult(transientMesh));
                transientMesh = null;
            }
            assertEquals((long) batch, statsSupplier.get().get("tracked"));

            for (int attempt = 0; attempt < 25 && weakRefs.stream().anyMatch(ref -> ref.get() != null); attempt++) {
                System.gc();
                Thread.sleep(20L);
            }
            assertTrue(weakRefs.stream().allMatch(ref -> ref.get() == null), "dead meshes must be GC-collectable");
            assertEquals(0L, statsSupplier.get().get("tracked"), "tracked count must exclude collected meshes");

            final Object liveMesh = meshType.getConstructor().newInstance();
            host.complete(liveMesh);
            final java.util.concurrent.atomic.AtomicReference<Throwable> workerFailure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            final var latch = new java.util.concurrent.CountDownLatch(1);
            final Thread[] workers = new Thread[4];
            for (int t = 0; t < workers.length; t++) {
                final int id = t;
                workers[t] = new Thread(() -> {
                    try {
                        latch.await();
                        for (int iter = 0; iter < 500; iter++) {
                            if (id == 0) {
                                host.consult(liveMesh);
                                host.complete(liveMesh);
                            } else if (id == 1) {
                                statsSupplier.get();
                            } else {
                                final Object ephemeral =
                                        meshType.getConstructor().newInstance();
                                host.complete(ephemeral);
                                host.consult(ephemeral);
                            }
                        }
                    } catch (Throwable problem) {
                        workerFailure.compareAndSet(null, problem);
                    }
                });
                workers[t].start();
            }
            latch.countDown();
            for (final Thread worker : workers) {
                worker.join(10_000L);
            }
            assertNull(workerFailure.get());
        }
    }
}
