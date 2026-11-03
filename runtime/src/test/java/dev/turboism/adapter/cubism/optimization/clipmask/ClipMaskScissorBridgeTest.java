package dev.turboism.adapter.cubism.optimization.clipmask;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.*;

import java.util.Map;
import java.util.Properties;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

/** Slot lifecycle and default preference for the scissor bridge on a synthetic reviewed layout. */
final class ClipMaskScissorBridgeTest {

    private static final class Loader extends ClassLoader {
        Loader() {
            super(ClipMaskScissorBridgeTest.class.getClassLoader());
        }

        Class<?> define(final String name, final byte[] bytes) {
            return defineClass(name.replace('/', '.'), bytes, 0, bytes.length);
        }
    }

    @AfterEach
    void clearPreference() {
        System.clearProperty(ClipMaskScissorBridge.ENABLE_PROPERTY);
    }

    @Test
    void installOccupiesAndCloseClearsAllSlots() throws Exception {
        final Loader loader = syntheticHost();
        final ClipMaskScissorBridge bridge = new ClipMaskScissorBridge(loader, "5.3.04");
        assertTrue(ClipMaskScissorBridge.enabledByPreference());
        bridge.install();

        final Properties properties = System.getProperties();
        assertTrue(properties.get(ClipMaskScissorBridge.CLEAR_PROPERTY) instanceof Function);
        assertTrue(properties.get(ClipMaskScissorBridge.STATS_PROPERTY) instanceof Supplier);
        assertThrows(IllegalStateException.class, bridge::install);

        final Map<String, Long> stats = bridge.snapshot();
        assertTrue(stats.get("active") == 1L);
        assertTrue(stats.get("enabled") == 1L);
        assertTrue(stats.get("clearPasses") == 0L);

        bridge.close();
        assertNull(properties.get(ClipMaskScissorBridge.CLEAR_PROPERTY));
        assertNull(properties.get(ClipMaskScissorBridge.STATS_PROPERTY));
        assertFalse(bridge.snapshot().get("active") == 1L);
    }

    @Test
    void explicitPreferenceCanDisableWithoutRemovingTheHook() throws Exception {
        final Loader loader = syntheticHost();
        final ClipMaskScissorBridge bridge = new ClipMaskScissorBridge(loader, "5.3.04");
        System.setProperty(ClipMaskScissorBridge.ENABLE_PROPERTY, "false");
        bridge.install();
        assertTrue(bridge.snapshot().get("enabled") == 0L);
        bridge.close();
    }

    private static Loader syntheticHost() throws Exception {
        final Loader loader = new Loader();
        loader.define(
                "com/live2d/graphics3d/rendering/a",
                interfaceClass(
                        "com/live2d/graphics3d/rendering/a",
                        new Method("a", "()Lcom/jogamp/opengl/GL;"),
                        new Method("b", "()Lcom/live2d/graphics3d/texture/GRenderTexture;")));
        loader.define(
                "com/jogamp/opengl/GL",
                interfaceClass(
                        "com/jogamp/opengl/GL",
                        new Method("glGetIntegerv", "(I[II)V"),
                        new Method("glIsEnabled", "(I)Z"),
                        new Method("glEnable", "(I)V"),
                        new Method("glDisable", "(I)V"),
                        new Method("glScissor", "(IIII)V")));
        loader.define(
                "com/live2d/graphics3d/texture/GRenderTexture",
                interfaceClass(
                        "com/live2d/graphics3d/texture/GRenderTexture",
                        new Method("getWidth", "()I"),
                        new Method("getHeight", "()I"),
                        new Method("getGlTextureName", "()I")));
        loader.define(
                "com/live2d/graphics3d/type/GMatrix44",
                interfaceClass("com/live2d/graphics3d/type/GMatrix44", new Method("a", "()[F")));
        loader.define(
                "com/live2d/graphics3d/GCamera",
                interfaceClass(
                        "com/live2d/graphics3d/GCamera",
                        new Method("getProjectionMatrix", "()Lcom/live2d/graphics3d/type/GMatrix44;"),
                        new Method("getWorldToCameraMatrix", "()Lcom/live2d/graphics3d/type/GMatrix44;")));
        loader.define(
                "com/live2d/graphics3d/a",
                interfaceClass("com/live2d/graphics3d/a", new Method("b", "()Lcom/live2d/graphics3d/GCamera;")));
        loader.define(
                "com/live2d/graphics3d/component/GTransform",
                interfaceClass(
                        "com/live2d/graphics3d/component/GTransform",
                        new Method("getLocalToWorldMatrix", "()Lcom/live2d/graphics3d/type/GMatrix44;")));
        loader.define(
                "com/live2d/graphics3d/component/GClipping",
                interfaceClass(
                        "com/live2d/graphics3d/component/GClipping",
                        new Method("getClippingVaildMasks", "()Ljava/util/List;")));
        loader.define(
                "com/live2d/graphics3d/component/GMeshContainer",
                interfaceClass(
                        "com/live2d/graphics3d/component/GMeshContainer",
                        new Method("getMesh", "()Lcom/live2d/graphics3d/mesh/a;")));
        loader.define(
                "com/live2d/graphics3d/mesh/a",
                interfaceClass(
                        "com/live2d/graphics3d/mesh/a",
                        new Method("getPositions", "()[F"),
                        new Method("getStep", "()I")));
        loader.define(
                "com/live2d/graphics3d/entity/GEntity",
                interfaceClass(
                        "com/live2d/graphics3d/entity/GEntity",
                        new Method("getComponents", "()Ljava/lang/Iterable;"),
                        new Method("getRenderer", "()Lcom/live2d/graphics3d/component/AGRenderer;"),
                        new Method("getTransform", "()Lcom/live2d/graphics3d/component/GTransform;")));
        loader.define(
                "com/live2d/graphics3d/component/AGRenderer",
                interfaceClass(
                        "com/live2d/graphics3d/component/AGRenderer",
                        new Method("getEntity", "()Lcom/live2d/graphics3d/entity/GEntity;")));
        loader.define(
                "com/live2d/graphics3d/component/GMeshRenderer",
                interfaceClass(
                        "com/live2d/graphics3d/component/GMeshRenderer",
                        new Method("getMeshContainer", "()Lcom/live2d/graphics3d/component/GMeshContainer;")));
        loader.define("com/live2d/graphics3d/rendering/e", renderSystemInterface());
        return loader;
    }

    private static byte[] renderSystemInterface() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                V17,
                ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE,
                "com/live2d/graphics3d/rendering/e",
                null,
                "java/lang/Object",
                null);
        writer.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "f", "Lcom/live2d/graphics3d/rendering/a;", null, null)
                .visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] interfaceClass(final String name, final Method... methods) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, name, null, "java/lang/Object", null);
        for (final Method method : methods) {
            final MethodVisitor visitor =
                    writer.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, method.name(), method.descriptor(), null, null);
            visitor.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private record Method(String name, String descriptor) {}
}
