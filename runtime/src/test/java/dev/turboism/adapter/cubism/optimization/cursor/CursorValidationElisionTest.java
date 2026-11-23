package dev.turboism.adapter.cubism.optimization.cursor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Panel;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Verifies JRT bytecode transformation and bridge callback contracts for AWT cursor elision.
 */
final class CursorValidationElisionTest {

    private static final URI JRT_ROOT_URI = URI.create("jrt:/");

    @Test
    void jrtContainerAndComponentRewriteCleanlyAndRejectForeignLoaders() throws Exception {
        final byte[] containerRef = Files.readAllBytes(
                FileSystems.getFileSystem(JRT_ROOT_URI).getPath(CursorValidationElisionTarget.CONTAINER_JRT_PATH));
        final byte[] componentRef = Files.readAllBytes(
                FileSystems.getFileSystem(JRT_ROOT_URI).getPath(CursorValidationElisionTarget.COMPONENT_JRT_PATH));

        final CursorValidationElisionTransformer transformer =
                new CursorValidationElisionTransformer(containerRef, componentRef);

        final byte[] rewrittenContainer = transformer.transform(
                null, null, CursorValidationElisionTarget.CONTAINER_OWNER, null, null, containerRef);
        assertNotNull(rewrittenContainer, transformer.failure());
        assertNull(transformer.failure());
        assertTrue(new ClassReader(rewrittenContainer)
                .getClassName()
                .equals(CursorValidationElisionTarget.CONTAINER_OWNER));

        final byte[] reconstitutedComponent = stripDeprecatedAttribute(componentRef);
        final byte[] rewrittenComponent = transformer.transform(
                null, null, CursorValidationElisionTarget.COMPONENT_OWNER, null, null, reconstitutedComponent);
        assertNotNull(rewrittenComponent, transformer.failure());
        assertNull(transformer.failure());
        assertEquals(2, transformer.matches());
        assertEquals(5, transformer.sites());

        try (URLClassLoader foreignLoader = new URLClassLoader(new URL[0])) {
            final ProtectionDomain foreignDomain = new ProtectionDomain(
                    new CodeSource(new URL("file:///tmp/foreign.jar"), (java.security.cert.Certificate[]) null), null);
            assertNull(transformer.transform(
                    null,
                    foreignLoader,
                    CursorValidationElisionTarget.CONTAINER_OWNER,
                    null,
                    foreignDomain,
                    containerRef));
            assertNotNull(transformer.failure());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void bridgePublishesAndClearsSlotsAndElidesUnmountedContainers() {
        try (CursorValidationElisionBridge bridge = new CursorValidationElisionBridge()) {
            bridge.install();
            final Predicate<Object> outside =
                    (Predicate<Object>) System.getProperties().get(CursorValidationElisionBridge.OUTSIDE_PROPERTY);
            assertNotNull(outside);
            assertTrue(outside.test(new Panel()));
            assertFalse(outside.test("not-a-component"));
            assertEquals(1L, bridge.snapshot().get("mousePosElided"));
        }
        assertNull(System.getProperties().get(CursorValidationElisionBridge.VALIDATE_PROPERTY));
        assertNull(System.getProperties().get(CursorValidationElisionBridge.OUTSIDE_PROPERTY));
        assertNull(System.getProperties().get(CursorValidationElisionBridge.DIRTY_PROPERTY));
    }

    // Simulates HotSpot JvmtiClassFileReconstituter stripping Deprecated attributes during retransformClasses.
    private static byte[] stripDeprecatedAttribute(final byte[] classBytes) {
        final ClassReader reader = new ClassReader(classBytes);
        final ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        return super.visitMethod(
                                access & ~Opcodes.ACC_DEPRECATED, name, descriptor, signature, exceptions);
                    }
                },
                0);
        return writer.toByteArray();
    }

    @Test
    void rewritesJdk25ContainerGetMousePositionCallingMouseInfo() {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                CursorValidationElisionTarget.CONTAINER_OWNER,
                null,
                "java/lang/Object",
                null);
        final MethodVisitor validate = writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                CursorValidationElisionTarget.VALIDATE_METHOD,
                CursorValidationElisionTarget.VALIDATE_DESCRIPTOR,
                null,
                null);
        validate.visitCode();
        validate.visitVarInsn(Opcodes.ALOAD, 0);
        validate.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                CursorValidationElisionTarget.CONTAINER_OWNER,
                CursorValidationElisionTarget.UPDATE_CURSOR_METHOD,
                CursorValidationElisionTarget.UPDATE_CURSOR_DESCRIPTOR,
                false);
        validate.visitInsn(Opcodes.RETURN);
        validate.visitMaxs(1, 1);
        validate.visitEnd();

        final MethodVisitor updateCursor = writer.visitMethod(
                Opcodes.ACC_FINAL,
                CursorValidationElisionTarget.UPDATE_CURSOR_METHOD,
                CursorValidationElisionTarget.UPDATE_CURSOR_DESCRIPTOR,
                null,
                null);
        updateCursor.visitCode();
        updateCursor.visitInsn(Opcodes.RETURN);
        updateCursor.visitMaxs(0, 1);
        updateCursor.visitEnd();

        final MethodVisitor getMousePos = writer.visitMethod(
                Opcodes.ACC_PUBLIC,
                CursorValidationElisionTarget.GET_MOUSE_POSITION_METHOD,
                CursorValidationElisionTarget.GET_MOUSE_POSITION_DESCRIPTOR,
                null,
                null);
        getMousePos.visitCode();
        getMousePos.visitMethodInsn(
                Opcodes.INVOKESTATIC, "java/awt/MouseInfo", "getPointerInfo", "()Ljava/awt/PointerInfo;", false);
        getMousePos.visitInsn(Opcodes.POP);
        getMousePos.visitInsn(Opcodes.ACONST_NULL);
        getMousePos.visitInsn(Opcodes.ARETURN);
        getMousePos.visitMaxs(1, 2);
        getMousePos.visitEnd();
        writer.visitEnd();

        final ClassWriter componentWriter = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        componentWriter.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                CursorValidationElisionTarget.COMPONENT_OWNER,
                null,
                "java/lang/Object",
                null);
        final MethodVisitor setCursor = componentWriter.visitMethod(
                Opcodes.ACC_PUBLIC,
                CursorValidationElisionTarget.SET_CURSOR_METHOD,
                CursorValidationElisionTarget.SET_CURSOR_DESCRIPTOR,
                null,
                null);
        setCursor.visitCode();
        setCursor.visitInsn(Opcodes.RETURN);
        setCursor.visitMaxs(0, 2);
        setCursor.visitEnd();

        final MethodVisitor show = componentWriter.visitMethod(
                Opcodes.ACC_PUBLIC,
                CursorValidationElisionTarget.SHOW_METHOD,
                CursorValidationElisionTarget.SHOW_DESCRIPTOR,
                null,
                null);
        show.visitCode();
        show.visitInsn(Opcodes.RETURN);
        show.visitMaxs(0, 1);
        show.visitEnd();

        final MethodVisitor hide = componentWriter.visitMethod(
                Opcodes.ACC_PUBLIC,
                CursorValidationElisionTarget.HIDE_METHOD,
                CursorValidationElisionTarget.HIDE_DESCRIPTOR,
                null,
                null);
        hide.visitCode();
        hide.visitInsn(Opcodes.RETURN);
        hide.visitMaxs(0, 1);
        hide.visitEnd();
        componentWriter.visitEnd();

        final byte[] containerBytes = writer.toByteArray();
        final byte[] componentBytes = componentWriter.toByteArray();
        final CursorValidationElisionTransformer transformer =
                new CursorValidationElisionTransformer(containerBytes, componentBytes);
        final byte[] rewritten = transformer.transform(
                null, null, CursorValidationElisionTarget.CONTAINER_OWNER, null, null, containerBytes);
        assertNotNull(rewritten, () -> "JDK 25 MouseInfo shape should be admitted: " + transformer.failure());
        assertEquals(2, transformer.sites());
    }
}
