package dev.turboism.validation.tilepatch;

import java.util.LinkedHashMap;
import java.util.Map;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Replaces the body of the private workaround
 * {@code com/live2d/util/f/g.a(BufferedImage,Graphics2D,BufferedImage,int,int)}
 * (5.3.x) or {@code com/live2d/util/e/g.a(...)} (5.2.03 — identical shape under the
 * earlier obfuscation bucket) with a delegate call to {@link TiledDrawDelegate}.
 *
 * <p>Fails closed in two layers:</p>
 * <ol>
 *   <li><b>Shape scan</b> — the method must contain the verified 5303 anchors:
 *       4x {@code UtCache.getBufferedImage}, 8x {@code UtCache.release}
 *       (normal+exceptional), {@code f.a(BI,BI,I)}, {@code h.a(BI,I)},
 *       {@code f.c} x2, {@code f.f} x2, 3x {@code drawImage},
 *       {@code i.a/i.b/i.c}, 2x {@code Arrays.fill}. Any deviation → unmodified.</li>
 *   <li><b>Rewrite</b> — emits only {@code TiledDrawDelegate.draw(a1,a2,a3,a4,a5);
 *       return}; original instructions and exception table are dropped.</li>
 * </ol>
 */
final class TilePatchTransformer {

    static final String OWNER = "com/live2d/util/f/g";
    /**
     * The 5.2.03 host carries the identical workaround under {@code com/live2d/util/e/g};
     * the rewritten body references only the delegate, so the same shape gate and emit
     * path serve both owners.
     */
    static final String OWNER_5203 = "com/live2d/util/e/g";
    static final String METHOD_NAME = "a";
    static final String METHOD_DESC =
        "(Ljava/awt/image/BufferedImage;Ljava/awt/Graphics2D;Ljava/awt/image/BufferedImage;II)V";
    static final String DELEGATE = "dev/turboism/validation/tilepatch/TiledDrawDelegate";

    /** Second instrumentation site: hash the produced page image at
     *  {@code setupCacheImage$cubism} return — applied in BOTH hashOnly and
     *  tiled runs so the digest is directly comparable. */
    static final String ATLAS_OWNER =
        "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas";
    static final String ATLAS_METHOD = "setupCacheImage$cubism";
    static final String ATLAS_DESC = "(ZLcom/live2d/util/a/a;)V";
    static final String PROBE = "dev/turboism/validation/tilepatch/TilePatchProbe";

    private TilePatchTransformer() {
    }

    record Outcome(byte[] bytes, Map<String, Integer> anchors, boolean patched) {
    }

    /** Instruments the atlas page-hash hook. Stack-neutral: at each RETURN it
     *  emits {@code aload_0; invokestatic TilePatchProbe.recordPageHash(Ljava/lang/Object;)V}. */
    static byte[] instrumentPageHash(final byte[] original, final String ownerInternalName) {
        if (!ATLAS_OWNER.equals(ownerInternalName)) return null;
        final boolean[] matched = {false};
        final ClassWriter writer = new ClassWriter(new ClassReader(original),
            ClassWriter.COMPUTE_MAXS);
        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name,
                                             final String descriptor, final String signature,
                                             final String[] exceptions) {
                final MethodVisitor delegate =
                    super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!ATLAS_METHOD.equals(name) || !ATLAS_DESC.equals(descriptor)
                    || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0)
                    return delegate;
                matched[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitInsn(final int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, PROBE,
                                "recordPageHash", "(Ljava/lang/Object;)V", false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        return matched[0] ? writer.toByteArray() : null;
    }

    static Outcome patch(final byte[] original, final String ownerInternalName) {
        if (!OWNER.equals(ownerInternalName) && !OWNER_5203.equals(ownerInternalName)) {
            return new Outcome(null, Map.of(), false);
        }

        // Pass 1: shape verification.
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
                        String key = owner + "." + nm;
                        // count only the anchors we require
                        switch (key) {
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
                                if (desc.equals("(Ljava/awt/image/BufferedImage;Ljava/awt/image/BufferedImage;I)V"))
                                    anchors.merge("f.copy", 1, Integer::sum);
                                else if (desc.equals("(Ljava/awt/image/BufferedImage;I)V"))
                                    anchors.merge("f.fill", 1, Integer::sum);
                            }
                            default -> { }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        if (!shapeOk(anchors)) return new Outcome(null, anchors, false);

        // Pass 2: rewrite.
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
                        delegate.visitVarInsn(Opcodes.ALOAD, 1);
                        delegate.visitVarInsn(Opcodes.ALOAD, 2);
                        delegate.visitVarInsn(Opcodes.ALOAD, 3);
                        delegate.visitVarInsn(Opcodes.ILOAD, 4);
                        delegate.visitVarInsn(Opcodes.ILOAD, 5);
                        delegate.visitMethodInsn(Opcodes.INVOKESTATIC, DELEGATE,
                            "draw", METHOD_DESC, false);
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
        return new Outcome(writer.toByteArray(), anchors, true);
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
}
