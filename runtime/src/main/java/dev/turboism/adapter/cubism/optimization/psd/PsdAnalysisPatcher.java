package dev.turboism.adapter.cubism.optimization.psd;

import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.IFNULL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.POP;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Patches {@code Step001_PreAnalyze}, {@code com.live2d.graphics.image.a.a}, and
 * {@code Step003_EdgeBlur_A} to use direct buffer downscaling and unrolled 3x3 convolutions.
 */
final class PsdAnalysisPatcher {

    static final String STEP001_PREANALYZE = "com/live2d/cubism/process/psd/algorithm/Step001_PreAnalyze";
    static final String EDGE_FILTER = "com/live2d/graphics/image/a/a";
    static final String STEP003_BLUR = "com/live2d/cubism/process/psd/algorithm/Step003_EdgeBlur_A";

    private static final String IMAGE_OPS = "dev/turboism/adapter/cubism/optimization/psd/PsdImageOps";
    private static final String WRITABLE_IMAGE = "com/live2d/graphics/CWritableImage";
    private static final String CREATE_REDUCED_METHOD = "createReduced";
    private static final String CREATE_REDUCED_DESC = "(II)Lcom/live2d/graphics/CWritableImage;";
    private static final String CONVOLVE_METHOD = "a";
    private static final String CONVOLVE_DESC =
            "(Lcom/live2d/graphics/CWritableImage;Lcom/live2d/graphics/CWritableImage;)Lcom/live2d/graphics/CWritableImage;";

    private static final Set<String> TARGETS = Set.of(STEP001_PREANALYZE, EDGE_FILTER, STEP003_BLUR);

    private PsdAnalysisPatcher() {}

    static boolean isSupportedTarget(final String internalName) {
        return internalName != null && TARGETS.contains(internalName);
    }

    static byte[] tryPatch(final String internalName, final ClassLoader loader, final byte[] classfileBuffer) {
        if (STEP001_PREANALYZE.equals(internalName)) {
            return patchStep001(loader, classfileBuffer);
        }
        if (EDGE_FILTER.equals(internalName)) {
            return patchStep002EdgeFilter(loader, classfileBuffer);
        }
        if (STEP003_BLUR.equals(internalName)) {
            return patchStep003Blur(loader, classfileBuffer);
        }
        return null;
    }

    private static byte[] patchStep001(final ClassLoader loader, final byte[] classfileBuffer) {
        final ClassReader reader = HostClassWriters.newClassReader(classfileBuffer);
        if (PsdLayerDecodePatcher.hasCallToOwner(reader, IMAGE_OPS)) {
            return null;
        }
        final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
        final boolean[] patched = new boolean[1];
        final ClassVisitor visitor =
                new ClassVisitor(ASM9, HostClassWriters.preservingClassVersion(classfileBuffer, writer)) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                        return new MethodVisitor(ASM9, mv) {
                            @Override
                            public void visitMethodInsn(
                                    final int opcode,
                                    final String owner,
                                    final String mName,
                                    final String mDesc,
                                    final boolean isInterface) {
                                if (opcode == INVOKEVIRTUAL
                                        && WRITABLE_IMAGE.equals(owner)
                                        && CREATE_REDUCED_METHOD.equals(mName)
                                        && CREATE_REDUCED_DESC.equals(mDesc)) {
                                    patched[0] = true;
                                    super.visitMethodInsn(
                                            INVOKESTATIC,
                                            IMAGE_OPS,
                                            "fastCreateReducedOrDefault",
                                            "(Ljava/lang/Object;II)Ljava/lang/Object;",
                                            false);
                                    super.visitTypeInsn(CHECKCAST, WRITABLE_IMAGE);
                                    return;
                                }
                                super.visitMethodInsn(opcode, owner, mName, mDesc, isInterface);
                            }
                        };
                    }
                };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return patched[0] ? writer.toByteArray() : null;
    }

    private static byte[] patchStep002EdgeFilter(final ClassLoader loader, final byte[] classfileBuffer) {
        final ClassReader reader = HostClassWriters.newClassReader(classfileBuffer);
        if (PsdLayerDecodePatcher.hasCallToOwner(reader, IMAGE_OPS)) {
            return null;
        }
        final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
        final boolean[] patched = new boolean[1];
        final ClassVisitor visitor =
                new ClassVisitor(ASM9, HostClassWriters.preservingClassVersion(classfileBuffer, writer)) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                        if (!CONVOLVE_METHOD.equals(name) || !CONVOLVE_DESC.equals(desc)) {
                            return mv;
                        }
                        patched[0] = true;
                        return new MethodVisitor(ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                final Label fallback = new Label();
                                super.visitVarInsn(ALOAD, 0);
                                super.visitVarInsn(ALOAD, 1);
                                super.visitVarInsn(ALOAD, 2);
                                super.visitVarInsn(ALOAD, 0);
                                super.visitFieldInsn(GETFIELD, EDGE_FILTER, "b", "[I");
                                super.visitVarInsn(ALOAD, 0);
                                super.visitFieldInsn(GETFIELD, EDGE_FILTER, "c", "I");
                                super.visitVarInsn(ALOAD, 0);
                                super.visitFieldInsn(GETFIELD, EDGE_FILTER, "d", "I");
                                super.visitMethodInsn(
                                        INVOKESTATIC,
                                        IMAGE_OPS,
                                        "tryFastStep002EdgeFilter",
                                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;[III)Ljava/lang/Object;",
                                        false);
                                super.visitInsn(DUP);
                                super.visitJumpInsn(IFNULL, fallback);
                                super.visitTypeInsn(CHECKCAST, WRITABLE_IMAGE);
                                super.visitInsn(ARETURN);
                                super.visitLabel(fallback);
                                super.visitInsn(POP);
                            }
                        };
                    }
                };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return patched[0] ? writer.toByteArray() : null;
    }

    private static byte[] patchStep003Blur(final ClassLoader loader, final byte[] classfileBuffer) {
        final ClassReader reader = HostClassWriters.newClassReader(classfileBuffer);
        if (PsdLayerDecodePatcher.hasCallToOwner(reader, IMAGE_OPS)) {
            return null;
        }
        final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
        final boolean[] patched = new boolean[1];
        final ClassVisitor visitor =
                new ClassVisitor(ASM9, HostClassWriters.preservingClassVersion(classfileBuffer, writer)) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                        if (!CONVOLVE_METHOD.equals(name) || !CONVOLVE_DESC.equals(desc)) {
                            return mv;
                        }
                        patched[0] = true;
                        return new MethodVisitor(ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                final Label fallback = new Label();
                                super.visitVarInsn(ALOAD, 1);
                                super.visitVarInsn(ALOAD, 2);
                                super.visitFieldInsn(GETSTATIC, STEP003_BLUR, "c", "[I");
                                super.visitFieldInsn(GETSTATIC, STEP003_BLUR, "d", "I");
                                super.visitMethodInsn(
                                        INVOKESTATIC,
                                        IMAGE_OPS,
                                        "tryFastStep003Blur",
                                        "(Ljava/lang/Object;Ljava/lang/Object;[II)Ljava/lang/Object;",
                                        false);
                                super.visitInsn(DUP);
                                super.visitJumpInsn(IFNULL, fallback);
                                super.visitTypeInsn(CHECKCAST, WRITABLE_IMAGE);
                                super.visitInsn(ARETURN);
                                super.visitLabel(fallback);
                                super.visitInsn(POP);
                            }
                        };
                    }
                };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return patched[0] ? writer.toByteArray() : null;
    }
}
