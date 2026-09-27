package dev.turboism.adapter.cubism.optimization.composite;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.util.Properties;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Verifies the test-only canvas-composite elision on synthetic classes carrying
 * the exact reviewed owners and method signatures. The JDK dispatcher target
 * is attested by loader (bootstrap/platform) + the jrt-shape gate; the FlatLaf
 * target by host loader + exact code source. Synthetic bodies play the role of
 * the reviewed method shapes (a constant return / counter increment).
 */
public class CanvasCompositeElisionTransformerTest {

    private static final String PAINT = "javax/swing/RepaintManager$PaintManager";
    private static final String FILL = "com/formdev/flatlaf/ui/FlatPanelUI";

    private static final class Loader extends ClassLoader {
        private final ProtectionDomain domain;
        Loader(Path artifact) throws Exception {
            super(CanvasCompositeElisionTransformerTest.class.getClassLoader());
            domain = new ProtectionDomain(
                new CodeSource(artifact.toUri().toURL(), (java.security.CodeSigner[]) null),
                new Permissions());
        }
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name.replace('/', '.'), bytes, 0, bytes.length, domain);
        }
    }

    @AfterEach void clearSlots() {
        final Properties properties = System.getProperties();
        properties.remove(CanvasCompositeElisionBridge.PAINT_PROPERTY);
        properties.remove(CanvasCompositeElisionBridge.FILL_PROPERTY);
    }

    /**
     * Synthetic dispatcher: {@code paint(JComponent,JComponent,Graphics,
     * int,int,int,int)Z} returning true; {@code drift} adds a NOP so the
     * reviewed shape no longer matches.
     */
    private static byte[] paintManager(boolean drift) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, PAINT, null, "java/lang/Object", null);
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        m = w.visitMethod(ACC_PUBLIC, "paint",
            "(Ljavax/swing/JComponent;Ljavax/swing/JComponent;Ljava/awt/Graphics;IIII)Z",
            null, null);
        m.visitCode();
        if (drift) m.visitInsn(NOP);
        m.visitInsn(ICONST_1);
        m.visitInsn(IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /**
     * Synthetic FlatPanelUI: {@code update(Graphics,JComponent)V} incrementing
     * a public counter as the observable "fill".
     */
    private static byte[] flatPanel(boolean drift, boolean missing) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, FILL, null, "java/lang/Object", null);
        w.visitField(ACC_PUBLIC, "fills", "I", null, null).visitEnd();
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        if (!missing) {
            m = w.visitMethod(ACC_PUBLIC, "update",
                "(Ljava/awt/Graphics;Ljavax/swing/JComponent;)V", null, null);
            m.visitCode();
            if (drift) m.visitInsn(NOP);
            m.visitVarInsn(ALOAD, 0);
            m.visitInsn(DUP);
            m.visitFieldInsn(GETFIELD, FILL, "fills", "I");
            m.visitInsn(ICONST_1);
            m.visitInsn(IADD);
            m.visitFieldInsn(PUTFIELD, FILL, "fills", "I");
            m.visitInsn(RETURN);
            m.visitMaxs(0, 0);
            m.visitEnd();
        }
        w.visitEnd();
        return w.toByteArray();
    }

    @Test void matchingShapesInjectBothSitesAndHonorSlots() throws Exception {
        Path flatlaf = Files.createTempFile("flatlaf", ".jar");
        Loader loader = new Loader(flatlaf);
        var transformer = new CanvasCompositeElisionTransformer(
            loader, flatlaf, paintManager(false), flatPanel(false, false));

        // The JDK dispatcher: bootstrap attestation (null loader, null domain).
        byte[] paintOut = transformer.transform(null, null, PAINT, null, null,
            paintManager(false));
        assertNotNull(paintOut, transformer.failure());
        Class<?> pm = new Loader(flatlaf).define(PAINT, paintOut);
        Object dispatcher = pm.getDeclaredConstructor().newInstance();
        Method paint = pm.getMethod("paint", javax.swing.JComponent.class,
            javax.swing.JComponent.class, java.awt.Graphics.class,
            int.class, int.class, int.class, int.class);
        assertEquals(true, paint.invoke(dispatcher, null, null, null, 0, 0, 1, 1),
            "no slot → buffered path unchanged");
        System.getProperties().put(CanvasCompositeElisionBridge.PAINT_PROPERTY,
            (Predicate<Object>) c -> true);
        assertEquals(false, paint.invoke(dispatcher, null, null, null, 0, 0, 1, 1),
            "armed consult selects the direct-paint fallback");
        System.getProperties().put(CanvasCompositeElisionBridge.PAINT_PROPERTY,
            (Predicate<Object>) c -> false);
        assertEquals(true, paint.invoke(dispatcher, null, null, null, 0, 0, 1, 1));

        // The FlatLaf fill: host loader + exact code source.
        byte[] fillOut = transformer.transform(null, loader, FILL, null, loader.domain,
            flatPanel(false, false));
        assertNotNull(fillOut, transformer.failure());
        Class<?> fp = loader.define(FILL, fillOut);
        Object panel = fp.getDeclaredConstructor().newInstance();
        Method update = fp.getMethod("update", java.awt.Graphics.class,
            javax.swing.JComponent.class);
        update.invoke(panel, null, null);
        assertEquals(1, fp.getField("fills").getInt(panel));
        System.getProperties().put(CanvasCompositeElisionBridge.FILL_PROPERTY,
            (BiPredicate<Object, Object>) (g, c) -> true);
        update.invoke(panel, null, null);
        assertEquals(1, fp.getField("fills").getInt(panel),
            "armed fill consult must skip the background fill");

        assertEquals(2, transformer.matches());
        assertEquals(2, transformer.sites());
        assertNotNull(transformer.beforeSha256(PAINT));
        assertNotNull(transformer.beforeSha256(FILL));
    }

    @Test void mistypedOrThrowingSlotsFallThrough() throws Exception {
        Path flatlaf = Files.createTempFile("flatlaf", ".jar");
        Loader loader = new Loader(flatlaf);
        var transformer = new CanvasCompositeElisionTransformer(
            loader, flatlaf, paintManager(false), flatPanel(false, false));
        byte[] paintOut = transformer.transform(null, null, PAINT, null, null,
            paintManager(false));
        assertNotNull(paintOut, transformer.failure());
        Class<?> pm = new Loader(flatlaf).define(PAINT, paintOut);
        Object dispatcher = pm.getDeclaredConstructor().newInstance();
        Method paint = pm.getMethod("paint", javax.swing.JComponent.class,
            javax.swing.JComponent.class, java.awt.Graphics.class,
            int.class, int.class, int.class, int.class);

        final Properties properties = System.getProperties();
        properties.put(CanvasCompositeElisionBridge.PAINT_PROPERTY, "not-a-predicate");
        assertEquals(true, paint.invoke(dispatcher, null, null, null, 0, 0, 1, 1));
        properties.put(CanvasCompositeElisionBridge.PAINT_PROPERTY,
            (Predicate<Object>) c -> { throw new IllegalStateException("boom"); });
        assertEquals(true, paint.invoke(dispatcher, null, null, null, 0, 0, 1, 1),
            "a failing consult must fall back to the buffered path");
    }

    @Test void driftedBodiesAreRejected() throws Exception {
        Path flatlaf = Files.createTempFile("flatlaf", ".jar");
        Loader loader = new Loader(flatlaf);
        var transformer = new CanvasCompositeElisionTransformer(
            loader, flatlaf, paintManager(false), flatPanel(false, false));
        assertNull(transformer.transform(null, null, PAINT, null, null,
            paintManager(true)));
        assertNotNull(transformer.failure());
        assertNull(transformer.transform(null, loader, FILL, null, loader.domain,
            flatPanel(true, false)));
        assertEquals(0, transformer.matches());
        assertEquals(0, transformer.sites());
    }

    @Test void foreignLoadersAndArtifactsAreRejected() throws Exception {
        Path flatlaf = Files.createTempFile("flatlaf", ".jar");
        Loader loader = new Loader(flatlaf);
        var transformer = new CanvasCompositeElisionTransformer(
            loader, flatlaf, paintManager(false), flatPanel(false, false));
        // The JDK dispatcher must come from a JDK loader, not the host loader.
        assertNull(transformer.transform(null, loader, PAINT, null, loader.domain,
            paintManager(false)), "paint dispatcher from the app loader");
        assertNull(transformer.transform(null, null, "javax/swing/RepaintManager",
            null, null, paintManager(false)), "foreign JDK owner");
        // The FlatLaf target must come from the host loader + exact jar.
        assertNull(transformer.transform(null, new Loader(flatlaf), FILL, null,
            loader.domain, flatPanel(false, false)), "foreign loader");
        Loader alien = new Loader(Files.createTempFile("other", ".jar"));
        assertNull(transformer.transform(null, loader, FILL, null, alien.domain,
            flatPanel(false, false)), "flatlaf from another artifact");
        assertNotNull(transformer.failure());
        assertEquals(0, transformer.matches());
    }

    @Test void missingReviewedMethodFailsConstructor() throws Exception {
        Path flatlaf = Files.createTempFile("flatlaf", ".jar");
        Loader loader = new Loader(flatlaf);
        assertThrows(IllegalArgumentException.class, () ->
            new CanvasCompositeElisionTransformer(loader, flatlaf,
                paintManager(false), flatPanel(false, true)));
    }
}
