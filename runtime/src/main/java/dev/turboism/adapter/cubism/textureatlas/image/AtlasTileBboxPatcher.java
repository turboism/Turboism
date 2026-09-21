package dev.turboism.adapter.cubism.textureatlas.image;

import java.util.LinkedHashMap;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Rewrites the body of the host's private alpha workaround — {@code g.a(BufferedImage,
 * Graphics2D, BufferedImage, int, int)} under {@code com/live2d/util/f} on 5.3.x and
 * {@code com/live2d/util/e} on 5.2.03 — into a single {@code invokestatic
 * AtlasTileBboxDelegate.draw} call.
 *
 * <p>The host original performs page-sized scratch work per model image: two page-sized pool
 * buffers, a page-sized alpha merge loop and a page-sized SrcOver composite. The delegate runs the
 * identical algorithm with the scratch and merge bounds shrunk to the transformed-tile bounding box
 * clipped to the page, which was proven pixel-identical on the real 5.3.03 host on both the
 * editor-open and export paths.</p>
 *
 * <p>Fail-closed in two layers:</p>
 * <ol>
 *   <li><b>Shape gate</b> — the method must contain every verified anchor with its exact count;
 *       any deviation leaves the class unmodified.</li>
 *   <li><b>Rewrite</b> — emits only the delegate call and {@code return}; the original
 *       instructions, exception table and locals are dropped. Frames are preserved on the rest of
 *       the class — stripping them was proven to break verification.</li>
 * </ol>
 */
public final class AtlasTileBboxPatcher {

    static final String METHOD_NAME = "a";
    static final String METHOD_DESC =
        "(Ljava/awt/image/BufferedImage;Ljava/awt/Graphics2D;Ljava/awt/image/BufferedImage;II)V";
    /** Internal name of the delegate shipped in the agent jar. */
    static final String DELEGATE = "dev/turboism/bootstrap/tilebbox/AtlasTileBboxDelegate";
    static final String DELEGATE_METHOD = "draw";
    /**
     * The delegate signature carries the patched class object first: the {@code ldc} resolves
     * it in the host class's own loader context, which hands the bootstrap-loaded delegate the
     * one loader that can see the {@code jp.noids.*} helpers it invokes reflectively.
     */
    static final String DELEGATE_DESC = "(Ljava/lang/Class;" + METHOD_DESC.substring(1);

    private AtlasTileBboxPatcher() {
    }

    /** Thrown when the observed method shape differs from the reviewed 5.3.03 structure. */
    public static final class NotApplicable extends Exception {
        NotApplicable(final String detail) {
            super(detail);
        }
    }

    /** Anchors observed in the target method, for diagnostics. */
    public record Anchors(Map<String, Integer> counts) {
    }

    /**
     * Returns the patched class bytes, or throws {@link NotApplicable} when the target method does
     * not match the reviewed shape.
     *
     * @param ownerInternalName the patched class's own internal name; the rewritten body loads it
     *                          with {@code ldc} so the delegate can resolve host helpers through
     *                          the patched class's loader
     */
    public static byte[] patch(final byte[] original, final String ownerInternalName)
            throws NotApplicable {
        final Map<String, Integer> anchors = scan(original);
        if (!shapeOk(anchors)) {
            throw new NotApplicable("anchor mismatch: " + anchors);
        }
        return rewrite(original, ownerInternalName);
    }

    /** Runs only the shape gate; the counts are returned for diagnostics. */
    public static Anchors inspect(final byte[] original) {
        return new Anchors(scan(original));
    }

    private static Map<String, Integer> scan(final byte[] original) {
        final Map<String, Integer> anchors = new LinkedHashMap<>();
        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name,
                                             final String descriptor, final String signature,
                                             final String[] exceptions) {
                if (!METHOD_NAME.equals(name) || !METHOD_DESC.equals(descriptor)
                    || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    return null;
                }
                anchors.merge("method", 1, Integer::sum);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(final int opcode, final String owner,
                                                final String nm, final String desc,
                                                final boolean isInterface) {
                        switch (owner + "." + nm) {
                            case "jp/noids/util/UtCache.getBufferedImage" ->
                                anchors.merge("pool.get", 1, Integer::sum);
                            case "jp/noids/util/UtCache.release" ->
                                anchors.merge("pool.release", 1, Integer::sum);
                            case "jp/noids/graphics/h.a" ->
                                anchors.merge("h.edgefill", 1, Integer::sum);
                            case "jp/noids/graphics/i.a" ->
                                anchors.merge("i.a", 1, Integer::sum);
                            case "jp/noids/graphics/i.b" ->
                                anchors.merge("i.b", 1, Integer::sum);
                            case "jp/noids/graphics/i.c" ->
                                anchors.merge("i.c", 1, Integer::sum);
                            case "java/awt/Graphics2D.drawImage" ->
                                anchors.merge("drawImage", 1, Integer::sum);
                            case "java/util/Arrays.fill" ->
                                anchors.merge("arraysFill", 1, Integer::sum);
                            case "jp/noids/graphics/f.c" ->
                                anchors.merge("f.c", 1, Integer::sum);
                            case "jp/noids/graphics/f.f" ->
                                anchors.merge("f.f", 1, Integer::sum);
                            case "jp/noids/graphics/f.a" -> {
                                if (desc.equals("(Ljava/awt/image/BufferedImage;Ljava/awt/image/BufferedImage;I)V")) {
                                    anchors.merge("f.copy", 1, Integer::sum);
                                } else if (desc.equals("(Ljava/awt/image/BufferedImage;I)V")) {
                                    anchors.merge("f.fill", 1, Integer::sum);
                                }
                            }
                            default -> { }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return anchors;
    }

    private static boolean shapeOk(final Map<String, Integer> a) {
        return a.getOrDefault("method", 0) == 1
            && a.getOrDefault("pool.get", 0) == 4
            && a.getOrDefault("pool.release", 0) == 8
            && a.getOrDefault("h.edgefill", 0) == 1
            && a.getOrDefault("f.copy", 0) == 1
            && a.getOrDefault("f.fill", 0) == 1
            && a.getOrDefault("f.c", 0) == 2
            && a.getOrDefault("f.f", 0) == 3
            && a.getOrDefault("i.a", 0) == 1
            && a.getOrDefault("i.b", 0) == 1
            && a.getOrDefault("i.c", 0) == 1
            && a.getOrDefault("drawImage", 0) == 3
            && a.getOrDefault("arraysFill", 0) == 2;
    }

    private static byte[] rewrite(final byte[] original, final String ownerInternalName) {
        final ClassWriter writer = new ClassWriter(new ClassReader(original),
            ClassWriter.COMPUTE_MAXS);
        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name,
                                             final String descriptor, final String signature,
                                             final String[] exceptions) {
                final MethodVisitor delegate =
                    super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!METHOD_NAME.equals(name) || !METHOD_DESC.equals(descriptor)
                    || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                    return delegate;
                }
                // Swallow the entire original body (instructions, frames, try/catch,
                // locals); emit only the delegate call. Forward visitMaxs/visitEnd
                // so the writer finalizes the method correctly.
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitCode() {
                        delegate.visitCode();
                        delegate.visitLdcInsn(Type.getObjectType(ownerInternalName));
                        delegate.visitVarInsn(Opcodes.ALOAD, 1);
                        delegate.visitVarInsn(Opcodes.ALOAD, 2);
                        delegate.visitVarInsn(Opcodes.ALOAD, 3);
                        delegate.visitVarInsn(Opcodes.ILOAD, 4);
                        delegate.visitVarInsn(Opcodes.ILOAD, 5);
                        delegate.visitMethodInsn(Opcodes.INVOKESTATIC, DELEGATE,
                            DELEGATE_METHOD, DELEGATE_DESC, false);
                        delegate.visitInsn(Opcodes.RETURN);
                    }

                    @Override
                    public void visitMaxs(final int maxStack, final int maxLocals) {
                        delegate.visitMaxs(maxStack, maxLocals);
                    }

                    @Override
                    public void visitEnd() {
                        delegate.visitEnd();
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
        return writer.toByteArray();
    }
}
