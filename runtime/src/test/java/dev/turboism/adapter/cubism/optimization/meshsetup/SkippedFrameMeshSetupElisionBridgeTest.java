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
            host.context.getClass().getField("shared").setBoolean(host.context, true);
            assertFalse(host.consult(), "a shared context is not a reviewed render generation");
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
}
