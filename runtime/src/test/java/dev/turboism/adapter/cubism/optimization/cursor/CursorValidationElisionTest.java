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
}
