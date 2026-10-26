package dev.turboism.adapter.cubism.optimization.uploadelision;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipBridge;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkippedFrameUploadElisionBridgeTest {
    @TempDir
    Path directory;

    /** Real bridge reflection and callbacks against a small loader-isolated JOGL/host stand-in. */
    private final class Host implements AutoCloseable {
        final URLClassLoader loader;
        final Object gl, context, wrapper;
        final SkippedFrameUploadElisionBridge bridge;
        final Object oldSkipped;

        Host() throws Exception {
            var sources = Map.of(
                    "com/jogamp/opengl/GL.java", """
                        package com.jogamp.opengl;
                        public class GL {
                            public GLContext context = new GLContext();
                            public GLContext getContext() { return context; }
                        }
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
                    "com/live2d/graphics3d/mesh/a/a.java", """
                        package com.live2d.graphics3d.mesh.a;
                        public class a {
                            public java.nio.IntBuffer i() { return java.nio.IntBuffer.wrap(new int[] {7}); }
                        }
                        """,
                    "com/live2d/graphics3d/mesh/a/b.java", """
                        package com.live2d.graphics3d.mesh.a;
                        public class b extends a {
                            public java.nio.FloatBuffer b() { return java.nio.FloatBuffer.wrap(new float[] {1, 2}); }
                        }
                        """,
                    "com/live2d/graphics3d/shader/A.java", """
                        package com.live2d.graphics3d.shader;
                        public class A {
                            public static final A a = new A();
                            public long a(java.nio.Buffer buffer) { return buffer.remaining() * 4L; }
                        }
                        """);
            var arguments = new java.util.ArrayList<String>();
            arguments.add("-d");
            arguments.add(directory.toString());
            for (var source : sources.entrySet()) {
                Path path = directory.resolve(source.getKey());
                Files.createDirectories(path.getParent());
                Files.writeString(path, source.getValue());
                arguments.add(path.toString());
            }
            assertEquals(
                    0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
            loader = new URLClassLoader(new java.net.URL[] {directory.toUri().toURL()}, null);
            gl = loader.loadClass("com.jogamp.opengl.GL").getConstructor().newInstance();
            context = gl.getClass().getField("context").get(gl);
            context.getClass().getField("current").set(null, context);
            wrapper = loader.loadClass("com.live2d.graphics3d.mesh.a.b")
                    .getConstructor()
                    .newInstance();
            oldSkipped =
                    System.getProperties().put(ModelUpdateSkipBridge.SKIPPED_FRAME_PROPERTY, new AtomicBoolean(true));
            bridge = new SkippedFrameUploadElisionBridge(loader, true);
            bridge.install();
        }

        @SuppressWarnings("unchecked")
        boolean consult() {
            return ((BiPredicate<Object, Object>)
                            System.getProperties().get(SkippedFrameUploadElisionBridge.PREDICATE_PROPERTY))
                    .test(wrapper, gl);
        }

        void returned() {
            ((Runnable) System.getProperties().get(SkippedFrameUploadElisionBridge.RETURN_PROPERTY)).run();
        }

        void establish() {
            assertFalse(consult());
            returned();
            SkippedFrameUploadElisionBridge.observeError(gl, 0);
            assertTrue(consult());
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
    void uploadNeedsNativeReturnAndRealCheckAndSilentErrorInvalidatesIt() throws Exception {
        try (var host = new Host()) {
            assertFalse(host.consult());
            SkippedFrameUploadElisionBridge.observeError(host.gl, 0);
            assertFalse(host.consult());
            host.returned();
            assertFalse(host.consult());
            host.returned();
            SkippedFrameUploadElisionBridge.observeError(host.gl, 1282);
            assertFalse(host.consult());
            host.returned();
            SkippedFrameUploadElisionBridge.observeError(host.gl, 0);
            assertTrue(host.consult());
        }
    }

    @Test
    void sameGlWrapperCannotReuseAfterContextGenerationOrRenderBoundaryChanges() throws Exception {
        try (var host = new Host()) {
            host.establish();
            host.context.getClass().getField("handle").setLong(host.context, 2L);
            host.establish(); // Must upload again despite identical GL wrapper/name/content.
            SkippedFrameUploadElisionBridge.invalidateUploads();
            host.establish();
        }
    }

    @Test
    void uncreatedNoncurrentAndSharedContextsCannotConfirmUploads() throws Exception {
        try (var host = new Host()) {
            host.establish();
            host.context.getClass().getField("created").setBoolean(host.context, false);
            assertFalse(host.consult());
            SkippedFrameUploadElisionBridge.observeError(host.gl, 0);
            host.context.getClass().getField("created").setBoolean(host.context, true);
            host.establish();
            host.context.getClass().getField("current").set(null, null);
            assertFalse(host.consult());
            host.context.getClass().getField("current").set(null, host.context);
            host.establish();
            host.context.getClass().getField("shared").setBoolean(host.context, true);
            assertFalse(host.consult());
            host.returned();
            SkippedFrameUploadElisionBridge.observeError(host.gl, 0);
            assertFalse(host.consult());
        }
    }
}
