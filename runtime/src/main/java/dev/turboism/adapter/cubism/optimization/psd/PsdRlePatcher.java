package dev.turboism.adapter.cubism.optimization.psd;

import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASM9;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.DUP;
import static org.objectweb.asm.Opcodes.IFNULL;
import static org.objectweb.asm.Opcodes.ILOAD;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.POP;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Patches {@code com.live2d.graphics.psd.d.c} to route RLE decoding through {@link PsdRleBridge}.
 *
 * <p>Preserves full fallback into the original scanline decoder when {@link PsdRleBridge} returns
 * {@code null}.
 */
final class PsdRlePatcher {

    static final String TARGET_INTERNAL_NAME = "com/live2d/graphics/psd/d/c";

    private static final String BRIDGE_OWNER = "dev/turboism/adapter/cubism/optimization/psd/PsdRleBridge";
    private static final String RESULT_INTERNAL_NAME = "com/live2d/graphics/psd/d/c$a";
    private static final String DECODE_METHOD_NAME = "a";
    private static final String CHANNEL_DECODE_DESC =
            "(Lcom/live2d/graphics/psd/b;II[B)Lcom/live2d/graphics/psd/d/c$a;";
    private static final String MERGED_DECODE_DESC =
            "(Lcom/live2d/graphics/psd/b;III[[B)Lcom/live2d/graphics/psd/d/c$a;";
    private static final String BRIDGE_CHANNEL_METHOD = "tryDecodeChannel";
    private static final String BRIDGE_CHANNEL_DESC = "(Ljava/lang/Object;II[B)Ljava/lang/Object;";
    private static final String BRIDGE_MERGED_METHOD = "tryDecodeMerged";
    private static final String BRIDGE_MERGED_DESC = "(Ljava/lang/Object;III[[B)Ljava/lang/Object;";

    private static final int ARG_READER = 1;
    private static final int ARG_WIDTH = 2;
    private static final int ARG_HEIGHT = 3;
    private static final int ARG_CHANNELS = 4;
    private static final int ARG_SINGLE_OUT = 4;
    private static final int ARG_MERGED_OUTS = 5;

    private PsdRlePatcher() {}

    static byte[] tryPatch(final ClassLoader loader, final byte[] classfileBuffer) {
        final ClassReader reader = HostClassWriters.newClassReader(classfileBuffer);
        if (!TARGET_INTERNAL_NAME.equals(reader.getClassName())) {
            return null;
        }
        final RleScan scan = new RleScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (scan.alreadyPatched || !scan.hasBothTargets()) {
            return null;
        }

        final ClassWriter writer = HostClassWriters.newFrameWriter(reader, loader);
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
                        if (!DECODE_METHOD_NAME.equals(name)) {
                            return mv;
                        }
                        if (CHANNEL_DECODE_DESC.equals(desc)) {
                            return new MethodVisitor(ASM9, mv) {
                                @Override
                                public void visitCode() {
                                    super.visitCode();
                                    final Label fallback = new Label();
                                    super.visitVarInsn(ALOAD, ARG_READER);
                                    super.visitVarInsn(ILOAD, ARG_WIDTH);
                                    super.visitVarInsn(ILOAD, ARG_HEIGHT);
                                    super.visitVarInsn(ALOAD, ARG_SINGLE_OUT);
                                    super.visitMethodInsn(
                                            INVOKESTATIC,
                                            BRIDGE_OWNER,
                                            BRIDGE_CHANNEL_METHOD,
                                            BRIDGE_CHANNEL_DESC,
                                            false);
                                    super.visitInsn(DUP);
                                    super.visitJumpInsn(IFNULL, fallback);
                                    super.visitTypeInsn(CHECKCAST, RESULT_INTERNAL_NAME);
                                    super.visitInsn(ARETURN);
                                    super.visitLabel(fallback);
                                    super.visitInsn(POP);
                                }
                            };
                        }
                        if (MERGED_DECODE_DESC.equals(desc)) {
                            return new MethodVisitor(ASM9, mv) {
                                @Override
                                public void visitCode() {
                                    super.visitCode();
                                    final Label fallback = new Label();
                                    super.visitVarInsn(ALOAD, ARG_READER);
                                    super.visitVarInsn(ILOAD, ARG_WIDTH);
                                    super.visitVarInsn(ILOAD, ARG_HEIGHT);
                                    super.visitVarInsn(ILOAD, ARG_CHANNELS);
                                    super.visitVarInsn(ALOAD, ARG_MERGED_OUTS);
                                    super.visitMethodInsn(
                                            INVOKESTATIC,
                                            BRIDGE_OWNER,
                                            BRIDGE_MERGED_METHOD,
                                            BRIDGE_MERGED_DESC,
                                            false);
                                    super.visitInsn(DUP);
                                    super.visitJumpInsn(IFNULL, fallback);
                                    super.visitTypeInsn(CHECKCAST, RESULT_INTERNAL_NAME);
                                    super.visitInsn(ARETURN);
                                    super.visitLabel(fallback);
                                    super.visitInsn(POP);
                                }
                            };
                        }
                        return mv;
                    }
                };
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static final class RleScan extends ClassVisitor {

        private boolean hasChannelDecode;
        private boolean hasMergedDecode;
        private boolean alreadyPatched;

        RleScan() {
            super(ASM9);
        }

        boolean hasBothTargets() {
            return hasChannelDecode && hasMergedDecode;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String desc,
                final String signature,
                final String[] exceptions) {
            if (!DECODE_METHOD_NAME.equals(name)) {
                return null;
            }
            if (CHANNEL_DECODE_DESC.equals(desc)) {
                hasChannelDecode = true;
            } else if (MERGED_DECODE_DESC.equals(desc)) {
                hasMergedDecode = true;
            } else {
                return null;
            }
            return new MethodVisitor(ASM9) {
                @Override
                public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String mName,
                        final String mDesc,
                        final boolean isInterface) {
                    if (BRIDGE_OWNER.equals(owner)) {
                        alreadyPatched = true;
                    }
                }
            };
        }
    }
}
