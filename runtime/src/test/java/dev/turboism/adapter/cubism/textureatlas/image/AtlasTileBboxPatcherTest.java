package dev.turboism.adapter.cubism.textureatlas.image;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape-gate and rewrite tests for the tile-bbox patcher.
 *
 * <p>The fixture classes under {@code com.live2d.util.f} replicate the reviewed 5.3.03 shape —
 * the private page-size pipeline under a branched public method — without depending on, or
 * loading, any official class.</p>
 */
final class AtlasTileBboxPatcherTest {

    @Test
    void fixtureCarriesEveryReviewedAnchor() throws Exception {
        final Map<String, Integer> anchors =
            AtlasTileBboxPatcher.inspect(fixtureBytes("com/live2d/util/f/g.class")).counts();

        assertEquals(1, anchors.getOrDefault("method", 0));
        assertEquals(4, anchors.getOrDefault("pool.get", 0));
        assertEquals(8, anchors.getOrDefault("pool.release", 0));
        assertEquals(1, anchors.getOrDefault("h.edgefill", 0));
        assertEquals(1, anchors.getOrDefault("f.copy", 0));
        assertEquals(1, anchors.getOrDefault("f.fill", 0));
        assertEquals(2, anchors.getOrDefault("f.c", 0));
        assertEquals(3, anchors.getOrDefault("f.f", 0));
        assertEquals(1, anchors.getOrDefault("i.a", 0));
        assertEquals(1, anchors.getOrDefault("i.b", 0));
        assertEquals(1, anchors.getOrDefault("i.c", 0));
        assertEquals(3, anchors.getOrDefault("drawImage", 0));
        assertEquals(2, anchors.getOrDefault("arraysFill", 0));
    }

    @Test
    void shapeMismatchLeavesTheClassUnmodified() throws Exception {
        // Same package and same private signature but none of the reviewed anchors.
        final byte[] alien = fixtureBytes("com/live2d/util/f/Alien.class");
        assertThrows(AtlasTileBboxPatcher.NotApplicable.class,
            () -> AtlasTileBboxPatcher.patch(alien, "com/live2d/util/f/g"));
    }

    @Test
    void patchedBodyIsOnlyTheDelegateCall() throws Exception {
        final byte[] patched =
            AtlasTileBboxPatcher.patch(
            fixtureBytes("com/live2d/util/f/g.class"), "com/live2d/util/f/g");
        assertNotNull(patched);

        final int[] invokeStatic = {0};
        final int[] otherCalls = {0};
        final int[] returns = {0};
        final int[] ldcTarget = {0};
        new ClassReader(patched).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name,
                                             final String descriptor, final String signature,
                                             final String[] exceptions) {
                if (!"a".equals(name)
                    || !("(Ljava/awt/image/BufferedImage;Ljava/awt/Graphics2D;"
                        + "Ljava/awt/image/BufferedImage;II)V").equals(descriptor)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLdcInsn(final Object value) {
                        if (value instanceof org.objectweb.asm.Type type
                            && type.getInternalName().equals("com/live2d/util/f/g")) {
                            ldcTarget[0]++;
                        }
                    }

                    @Override
                    public void visitMethodInsn(final int opcode, final String owner,
                                                final String nm, final String desc,
                                                final boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                            && owner.equals(AtlasTileBboxPatcher.DELEGATE)
                            && nm.equals(AtlasTileBboxPatcher.DELEGATE_METHOD)
                            && desc.equals(AtlasTileBboxPatcher.DELEGATE_DESC)) {
                            invokeStatic[0]++;
                        } else {
                            otherCalls[0]++;
                        }
                    }

                    @Override
                    public void visitInsn(final int opcode) {
                        if (opcode == Opcodes.RETURN) returns[0]++;
                    }
                };
            }
        }, 0);

        assertEquals(1, ldcTarget[0], "the patched body must ldc the host class itself");
        assertEquals(1, invokeStatic[0], "the patched body must call the delegate exactly once");
        assertEquals(0, otherCalls[0], "no original call may survive the rewrite");
        assertEquals(1, returns[0]);
    }

    @Test
    void siblingMethodsKeepTheirStackMapFrames() throws Exception {
        // Frame stripping was proven to break host verification; the public branch method
        // must still carry its frames after the rewrite.
        final byte[] patched =
            AtlasTileBboxPatcher.patch(
            fixtureBytes("com/live2d/util/f/g.class"), "com/live2d/util/f/g");
        final int[] frames = {0};
        new ClassReader(patched).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name,
                                             final String descriptor, final String signature,
                                             final String[] exceptions) {
                if (!"a".equals(name)
                    || !("(Ljava/awt/image/BufferedImage;Ljava/awt/Graphics;"
                        + "Ljava/awt/image/BufferedImage;IIDZ)V").equals(descriptor)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFrame(final int type, final int nLocal,
                                           final Object[] local, final int nStack,
                                           final Object[] stack) {
                        frames[0]++;
                    }
                };
            }
        }, 0);
        assertTrue(frames[0] > 0, "the branched public method must keep its stack map frames");
    }

    static byte[] fixtureBytes(final String resource) throws Exception {
        try (InputStream in = AtlasTileBboxPatcherTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, "missing fixture resource " + resource);
            return in.readAllBytes();
        }
    }
}
